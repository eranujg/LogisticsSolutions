package com.aadvixon.tms.iam;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Application users with their roles and locations. Passwords are never stored
 * here; sign-in is handled by the identity provider (Keycloak locally, Cognito
 * in AWS) and linked through {@code externalSubject}.
 */
@RestController
@RequestMapping("/api/v1/users")
class UserController {

    static final String USER_TYPES = "STAFF|DRIVER|CLIENT|CONSIGNEE|BOOKING_AGENT|DELIVERY_AGENT|CROSSING_AGENT"
            + "|VEHICLE_OWNER|BROKER|VENDOR|AUDITOR|LABOUR_CONTRACTOR";

    record User(UUID id, String userType, String fullName, String email, String mobile, String status,
                UUID homeLocationId, UUID partyId, String preferredLanguage, OffsetDateTime accessExpiresAt,
                OffsetDateTime lastLoginAt, List<UUID> roleIds, List<UUID> locationIds) {
    }

    record UserRequest(
            @NotBlank @Pattern(regexp = USER_TYPES) String userType,
            @NotBlank @Size(max = 150) String fullName,
            @Email String email,
            @Pattern(regexp = "\\+?[0-9]{7,15}") String mobile,
            @Pattern(regexp = "INVITED|ACTIVE|SUSPENDED|DEACTIVATED") String status,
            UUID homeLocationId,
            UUID partyId,
            @Pattern(regexp = "en|hi|pa|fr") String preferredLanguage,
            OffsetDateTime accessExpiresAt,
            List<UUID> roleIds,
            List<UUID> locationIds) {

        @AssertTrue(message = "email or mobile is required")
        boolean isContactPresent() {
            return (email != null && !email.isBlank()) || (mobile != null && !mobile.isBlank());
        }
    }

    private final JdbcClient jdbc;

    UserController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<User> list(@RequestParam(required = false) String type, @RequestParam(required = false) String q) {
        TenantContext.requireTenantId();
        List<UUID> ids = jdbc.sql("""
                        SELECT id FROM app_user
                         WHERE deleted_at IS NULL
                           AND (CAST(:type AS text) IS NULL OR user_type = :type)
                           AND (CAST(:q AS text) IS NULL OR lower(full_name) LIKE :q OR email::text LIKE :q OR mobile LIKE :q)
                         ORDER BY full_name
                         LIMIT 500
                        """)
                .param("type", type)
                .param("q", q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%")
                .query(UUID.class)
                .list();
        return ids.stream().map(this::find).toList();
    }

    @GetMapping("/{id}")
    User get(@PathVariable UUID id) {
        TenantContext.requireTenantId();
        return find(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    User create(@Valid @RequestBody UserRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID id = jdbc.sql("""
                        INSERT INTO app_user (tenant_id, user_type, full_name, email, mobile, status, home_location_id,
                                              party_id, preferred_language, access_expires_at, created_by)
                        VALUES (:tenant, :type, :name, CAST(:email AS citext), :mobile, COALESCE(:status, 'INVITED'),
                                CAST(:home AS uuid), CAST(:party AS uuid), COALESCE(:lang, 'en'), :expires, :user)
                        RETURNING id
                        """)
                .param("tenant", tenantId)
                .param("user", TenantContext.currentUserId())
                .params(fields(request))
                .query(UUID.class)
                .single();
        replaceLinks(tenantId, id, request);
        return find(id);
    }

    @PutMapping("/{id}")
    @Transactional
    User update(@PathVariable UUID id, @Valid @RequestBody UserRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        int updated = jdbc.sql("""
                        UPDATE app_user
                           SET user_type = :type, full_name = :name, email = CAST(:email AS citext), mobile = :mobile,
                               status = COALESCE(:status, status), home_location_id = CAST(:home AS uuid),
                               party_id = CAST(:party AS uuid), preferred_language = COALESCE(:lang, preferred_language),
                               access_expires_at = :expires
                         WHERE id = :id AND deleted_at IS NULL
                        """)
                .param("id", id)
                .params(fields(request))
                .update();
        if (updated == 0) {
            throw new NotFoundException("User", id);
        }
        replaceLinks(tenantId, id, request);
        return find(id);
    }

    /** Deactivates and soft-deletes; history (GRs, audit) keeps the user reference. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable UUID id, @RequestParam String reason) {
        TenantContext.requireTenantId();
        int updated = jdbc.sql("""
                        UPDATE app_user SET status = 'DEACTIVATED', deleted_at = now(), deleted_by = :user,
                               delete_reason = :reason
                         WHERE id = :id AND deleted_at IS NULL
                        """)
                .param("user", TenantContext.currentUserId())
                .param("reason", reason)
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new NotFoundException("User", id);
        }
    }

    private void replaceLinks(UUID tenantId, UUID userId, UserRequest request) {
        if (request.roleIds() != null) {
            jdbc.sql("DELETE FROM user_role WHERE user_id = :u").param("u", userId).update();
            for (UUID roleId : request.roleIds()) {
                jdbc.sql("INSERT INTO user_role (tenant_id, user_id, role_id) VALUES (:t, :u, :r)")
                        .param("t", tenantId).param("u", userId).param("r", roleId).update();
            }
        }
        if (request.locationIds() != null) {
            jdbc.sql("DELETE FROM user_location WHERE user_id = :u").param("u", userId).update();
            for (UUID locationId : request.locationIds()) {
                jdbc.sql("INSERT INTO user_location (tenant_id, user_id, location_id) VALUES (:t, :u, :l)")
                        .param("t", tenantId).param("u", userId).param("l", locationId).update();
            }
        }
    }

    private static Map<String, Object> fields(UserRequest r) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", r.userType());
        m.put("name", r.fullName());
        m.put("email", r.email() == null || r.email().isBlank() ? null : r.email().trim());
        m.put("mobile", r.mobile() == null || r.mobile().isBlank() ? null : r.mobile().trim());
        m.put("status", r.status());
        m.put("home", r.homeLocationId());
        m.put("party", r.partyId());
        m.put("lang", r.preferredLanguage());
        m.put("expires", r.accessExpiresAt());
        return m;
    }

    private User find(UUID id) {
        List<UUID> roles = jdbc.sql("SELECT role_id FROM user_role WHERE user_id = :id").param("id", id)
                .query(UUID.class).list();
        List<UUID> locations = jdbc.sql("SELECT location_id FROM user_location WHERE user_id = :id").param("id", id)
                .query(UUID.class).list();
        return jdbc.sql("SELECT *, email::text AS email_text FROM app_user WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query((rs, n) -> map(rs, roles, locations))
                .optional()
                .orElseThrow(() -> new NotFoundException("User", id));
    }

    private static User map(ResultSet rs, List<UUID> roles, List<UUID> locations) throws SQLException {
        return new User(Rows.uuid(rs, "id"), rs.getString("user_type"), rs.getString("full_name"),
                rs.getString("email_text"), rs.getString("mobile"), rs.getString("status"),
                Rows.uuid(rs, "home_location_id"), Rows.uuid(rs, "party_id"), rs.getString("preferred_language"),
                Rows.timestamp(rs, "access_expires_at"), Rows.timestamp(rs, "last_login_at"), roles, locations);
    }
}

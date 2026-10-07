package com.aadvixon.tms.platform.security;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Turns a verified login (JWT) into a tenant and an application user.
 *
 * <ul>
 *   <li>Tenant: from the token's {@code groups} claim, Keycloak groups named
 *       {@code /tenants/<tenant-code>}. A user in several tenants picks one with the
 *       {@code X-Tenant-Code} header.</li>
 *   <li>User: the {@code app_user} whose {@code external_subject} equals the token
 *       subject. On first login an invited user with the same email is linked.</li>
 * </ul>
 */
@Component
public class CurrentUserResolver {

    public static final String TENANT_GROUP_PREFIX = "/tenants/";

    /** A resolved, active user in a tenant. */
    public record ResolvedUser(UUID tenantId, String tenantCode, UUID userId, String fullName, String email,
                               Set<String> permissions, List<UUID> locationIds) {
    }

    /** Login is valid but has no access; message is safe to show. */
    public static class AccessProblem extends RuntimeException {
        public AccessProblem(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;

    CurrentUserResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ResolvedUser resolve(String subject, String email, List<String> groups, String requestedTenantCode) {
        List<String> tenantCodes = groups == null ? List.of() : groups.stream()
                .filter(g -> g.startsWith(TENANT_GROUP_PREFIX))
                .map(g -> g.substring(TENANT_GROUP_PREFIX.length()))
                .filter(c -> !c.isBlank() && !c.contains("/"))
                .toList();
        if (tenantCodes.isEmpty()) {
            throw new AccessProblem("Your login is not assigned to any company. Ask your administrator.");
        }
        String tenantCode;
        if (requestedTenantCode != null && !requestedTenantCode.isBlank()) {
            if (!tenantCodes.contains(requestedTenantCode)) {
                throw new AccessProblem("Your login has no access to company " + requestedTenantCode + ".");
            }
            tenantCode = requestedTenantCode;
        } else if (tenantCodes.size() == 1) {
            tenantCode = tenantCodes.getFirst();
        } else {
            throw new AccessProblem("Choose a company with the X-Tenant-Code header: " + String.join(", ", tenantCodes));
        }

        UUID tenantId = TenantContext.runAsSystem(null, () -> jdbc
                .sql("SELECT id FROM tenant WHERE code = CAST(:code AS citext) AND status IN ('ACTIVE', 'READ_ONLY')")
                .param("code", tenantCode)
                .query(UUID.class)
                .optional())
                .orElseThrow(() -> new AccessProblem("Company " + tenantCode + " is not active."));

        return TenantContext.runAsTenant(tenantId, email, () -> {
            UserRow user = findBySubject(subject).or(() -> linkByEmail(subject, email))
                    .orElseThrow(() -> new AccessProblem("No user is set up for your login in company " + tenantCode + "."));
            if (!"ACTIVE".equals(user.status())) {
                throw new AccessProblem("Your user account is " + user.status().toLowerCase() + ".");
            }
            if (user.accessExpiresAt() != null && user.accessExpiresAt().isBefore(OffsetDateTime.now())) {
                throw new AccessProblem("Your access expired on " + user.accessExpiresAt().toLocalDate() + ".");
            }
            Set<String> permissions = new HashSet<>(jdbc.sql("""
                            SELECT DISTINCT rp.permission_code
                              FROM user_role ur
                              JOIN role r ON r.id = ur.role_id AND r.deleted_at IS NULL
                              JOIN role_permission rp ON rp.role_id = r.id
                             WHERE ur.user_id = :user
                            """)
                    .param("user", user.id())
                    .query(String.class)
                    .list());
            List<UUID> locations = jdbc.sql("SELECT location_id FROM user_location WHERE user_id = :user")
                    .param("user", user.id())
                    .query(UUID.class)
                    .list();
            return new ResolvedUser(tenantId, tenantCode, user.id(), user.fullName(),
                    user.email() == null ? email : user.email(), Set.copyOf(permissions), locations);
        });
    }

    private record UserRow(UUID id, String fullName, String email, String status, OffsetDateTime accessExpiresAt) {
    }

    private Optional<UserRow> findBySubject(String subject) {
        return jdbc.sql("""
                        SELECT id, full_name, email::text AS email, status, access_expires_at
                          FROM app_user WHERE external_subject = :sub AND deleted_at IS NULL
                        """)
                .param("sub", subject)
                .query((rs, n) -> new UserRow(Rows.uuid(rs, "id"), rs.getString("full_name"), rs.getString("email"),
                        rs.getString("status"), Rows.timestamp(rs, "access_expires_at")))
                .optional();
    }

    /** First login: link an invited user with the same email to this login. */
    private Optional<UserRow> linkByEmail(String subject, String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        UPDATE app_user
                           SET external_subject = :sub,
                               status = CASE WHEN status = 'INVITED' THEN 'ACTIVE' ELSE status END,
                               last_login_at = now()
                         WHERE email = CAST(:email AS citext) AND external_subject IS NULL AND deleted_at IS NULL
                        RETURNING id, full_name, email::text AS email, status, access_expires_at
                        """)
                .param("sub", subject)
                .param("email", email.trim())
                .query((rs, n) -> new UserRow(Rows.uuid(rs, "id"), rs.getString("full_name"), rs.getString("email"),
                        rs.getString("status"), Rows.timestamp(rs, "access_expires_at")))
                .optional();
    }
}

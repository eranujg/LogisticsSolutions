package com.aadvixon.tms.iam;

import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.BusinessRuleException;
import com.aadvixon.tms.platform.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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
 * Roles and their permissions. System roles (created for every new tenant) can
 * be copied but not edited or deleted, so there is always a known-good template.
 */
@RestController
@RequestMapping("/api/v1")
class RoleController {

    record Permission(String code, String module, String action, boolean sensitive) {
    }

    record RolePermission(String permissionCode, String scope) {
    }

    record Role(UUID id, String code, String name, String description, boolean system, List<RolePermission> permissions) {
    }

    record RoleRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9_]+") String code,
            @NotBlank @Size(max = 100) String name,
            String description,
            List<@Valid PermissionGrant> permissions) {
    }

    record PermissionGrant(@NotBlank String permissionCode,
                           @Pattern(regexp = "COMPANY|REGION|LOCATIONS|OWN") String scope) {
    }

    record CopyRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_]+") String code, @NotBlank String name) {
    }

    private final JdbcClient jdbc;

    RoleController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/permissions")
    List<Permission> permissions() {
        return jdbc.sql("SELECT * FROM permission ORDER BY module, action")
                .query((rs, n) -> new Permission(rs.getString("code"), rs.getString("module"),
                        rs.getString("action"), rs.getBoolean("sensitive")))
                .list();
    }

    @GetMapping("/roles")
    List<Role> list() {
        TenantContext.requireTenantId();
        List<UUID> ids = jdbc.sql("SELECT id FROM role WHERE deleted_at IS NULL ORDER BY is_system DESC, name")
                .query(UUID.class)
                .list();
        return ids.stream().map(this::find).toList();
    }

    @GetMapping("/roles/{id}")
    Role get(@PathVariable UUID id) {
        TenantContext.requireTenantId();
        return find(id);
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Role create(@Valid @RequestBody RoleRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID id = jdbc.sql("INSERT INTO role (tenant_id, code, name, description) VALUES (:t, upper(:code), :name, :d) RETURNING id")
                .param("t", tenantId)
                .param("code", request.code())
                .param("name", request.name())
                .param("d", request.description())
                .query(UUID.class)
                .single();
        replacePermissions(tenantId, id, request.permissions());
        return find(id);
    }

    @PostMapping("/roles/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Role copy(@PathVariable UUID id, @Valid @RequestBody CopyRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        Role source = find(id);
        UUID copyId = jdbc.sql("INSERT INTO role (tenant_id, code, name, description) VALUES (:t, upper(:code), :name, :d) RETURNING id")
                .param("t", tenantId)
                .param("code", request.code())
                .param("name", request.name())
                .param("d", "Copy of " + source.name())
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        INSERT INTO role_permission (tenant_id, role_id, permission_code, scope)
                        SELECT tenant_id, :copy, permission_code, scope FROM role_permission WHERE role_id = :source
                        """)
                .param("copy", copyId)
                .param("source", id)
                .update();
        return find(copyId);
    }

    @PutMapping("/roles/{id}")
    @Transactional
    Role update(@PathVariable UUID id, @Valid @RequestBody RoleRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        requireEditable(id);
        jdbc.sql("UPDATE role SET code = upper(:code), name = :name, description = :d WHERE id = :id")
                .param("code", request.code())
                .param("name", request.name())
                .param("d", request.description())
                .param("id", id)
                .update();
        replacePermissions(tenantId, id, request.permissions());
        return find(id);
    }

    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable UUID id, @RequestParam String reason) {
        TenantContext.requireTenantId();
        requireEditable(id);
        boolean inUse = jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_role WHERE role_id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
        if (inUse) {
            throw new BusinessRuleException("Remove this role from all users before deleting it.");
        }
        jdbc.sql("UPDATE role SET deleted_at = now(), deleted_by = :user, delete_reason = :reason WHERE id = :id")
                .param("user", TenantContext.currentUserId())
                .param("reason", reason)
                .param("id", id)
                .update();
    }

    private void requireEditable(UUID id) {
        Boolean system = jdbc.sql("SELECT is_system FROM role WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("Role", id));
        if (system) {
            throw new BusinessRuleException("System roles cannot be changed. Copy the role and edit the copy.");
        }
    }

    private void replacePermissions(UUID tenantId, UUID roleId, List<PermissionGrant> grants) {
        if (grants == null) {
            return;
        }
        jdbc.sql("DELETE FROM role_permission WHERE role_id = :id").param("id", roleId).update();
        for (PermissionGrant grant : grants) {
            jdbc.sql("INSERT INTO role_permission (tenant_id, role_id, permission_code, scope) VALUES (:t, :r, :p, :s)")
                    .param("t", tenantId)
                    .param("r", roleId)
                    .param("p", grant.permissionCode())
                    .param("s", grant.scope() == null ? "COMPANY" : grant.scope())
                    .update();
        }
    }

    private Role find(UUID id) {
        List<RolePermission> permissions = jdbc
                .sql("SELECT permission_code, scope FROM role_permission WHERE role_id = :id ORDER BY permission_code")
                .param("id", id)
                .query((rs, n) -> new RolePermission(rs.getString("permission_code"), rs.getString("scope")))
                .list();
        return jdbc.sql("SELECT * FROM role WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query((rs, n) -> new Role(id, rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getBoolean("is_system"), permissions))
                .optional()
                .orElseThrow(() -> new NotFoundException("Role", id));
    }
}

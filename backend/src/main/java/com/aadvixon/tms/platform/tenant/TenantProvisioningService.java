package com.aadvixon.tms.platform.tenant;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates a tenant and its default roles in one transaction. */
@Service
public class TenantProvisioningService {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    TenantProvisioningService(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public TenantAdminController.TenantResponse createTenant(String code, String name) {
        UUID tenantId = UUID.randomUUID();
        // System scope with the new tenant id: the tenant row and its default
        // roles pass the row-level security checks for that tenant.
        return TenantContext.runAsSystem(tenantId, () -> tx.execute(status -> {
            jdbc.sql("INSERT INTO tenant (id, code, name) VALUES (:id, :code, :name)")
                    .param("id", tenantId)
                    .param("code", code)
                    .param("name", name)
                    .update();
            jdbc.sql("SELECT provision_tenant_defaults()").query().singleValue();
            return find(tenantId);
        }));
    }

    public List<TenantAdminController.TenantResponse> listTenants() {
        return TenantContext.runAsSystem(null, () -> jdbc
                .sql("SELECT id, code::text AS code, name, status, created_at FROM tenant ORDER BY created_at")
                .query((rs, n) -> new TenantAdminController.TenantResponse(
                        Rows.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("status"), Rows.timestamp(rs, "created_at")))
                .list());
    }

    private TenantAdminController.TenantResponse find(UUID id) {
        return jdbc.sql("SELECT id, code::text AS code, name, status, created_at FROM tenant WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new TenantAdminController.TenantResponse(
                        Rows.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("status"), Rows.timestamp(rs, "created_at")))
                .single();
    }
}

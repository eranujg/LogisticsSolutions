package com.aadvixon.tms.platform.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Prepares every connection for the current {@link TenantContext}.
 *
 * <ul>
 *   <li>Tenant scope or no scope: {@code SET ROLE tms_app} and set {@code app.tenant_id}
 *       (empty when there is no tenant, so RLS tables return nothing).</li>
 *   <li>System scope: {@code RESET ROLE} (pool user) with the optional tenant id.</li>
 * </ul>
 *
 * <p>Before the application is ready (Flyway migrations, JPA bootstrap) connections are
 * passed through unchanged; see {@link TenancyConfiguration}.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    static final AtomicBoolean ARMED = new AtomicBoolean(false);

    private static final String SET_CONTEXT =
            "SELECT set_config('app.tenant_id', ?, false), set_config('app.user_id', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return prepare(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return prepare(super.getConnection(username, password));
    }

    private Connection prepare(Connection connection) throws SQLException {
        if (!ARMED.get()) {
            return connection;
        }
        TenantContext.Scope scope = TenantContext.current().orElse(null);
        boolean system = scope != null && scope.system();
        String tenantId = scope == null || scope.tenantId() == null ? "" : scope.tenantId().toString();
        String userId = scope == null || scope.userId() == null ? "" : scope.userId();
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute(system ? "RESET ROLE" : "SET ROLE tms_app");
            }
            try (PreparedStatement statement = connection.prepareStatement(SET_CONTEXT)) {
                statement.setString(1, tenantId);
                statement.setString(2, userId);
                statement.execute();
            }
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }
}

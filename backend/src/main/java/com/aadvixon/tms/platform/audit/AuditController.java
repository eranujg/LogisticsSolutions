package com.aadvixon.tms.platform.audit;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the audit log. Rows are written by database triggers and
 * cannot be changed or deleted. {@code oldData}/{@code newData} are JSON text.
 */
@RestController
@RequestMapping("/api/v1/audit")
class AuditController {

    record AuditEntry(long id, String tableName, String recordId, String action, List<String> changedFields,
                      String oldData, String newData, String userId, OffsetDateTime occurredAt) {
    }

    private final JdbcClient jdbc;

    AuditController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<AuditEntry> search(@RequestParam(required = false) String table,
                            @RequestParam(required = false) String recordId,
                            @RequestParam(required = false) String userId,
                            @RequestParam(defaultValue = "100") int limit) {
        TenantContext.requireTenantId();
        return jdbc.sql("""
                        SELECT id, table_name, record_id, action, changed_fields,
                               old_data::text AS old_data, new_data::text AS new_data, user_id, occurred_at
                          FROM audit_log
                         WHERE (CAST(:table AS text) IS NULL OR table_name = :table)
                           AND (CAST(:recordId AS text) IS NULL OR record_id = :recordId)
                           AND (CAST(:userId AS text) IS NULL OR user_id = :userId)
                         ORDER BY id DESC
                         LIMIT :limit
                        """)
                .param("table", table)
                .param("recordId", recordId)
                .param("userId", userId)
                .param("limit", Math.clamp(limit, 1, 500))
                .query((rs, n) -> new AuditEntry(
                        rs.getLong("id"), rs.getString("table_name"), rs.getString("record_id"),
                        rs.getString("action"), Rows.strings(rs, "changed_fields"),
                        rs.getString("old_data"), rs.getString("new_data"),
                        rs.getString("user_id"), Rows.timestamp(rs, "occurred_at")))
                .list();
    }
}

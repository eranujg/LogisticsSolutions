package com.aadvixon.tms.platform.recyclebin;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.BusinessRuleException;
import com.aadvixon.tms.platform.web.NotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recycle bin for soft-deleted master records: list, restore, and permanent
 * delete (blocked by legal holds and by records still in use).
 *
 * <p>Issued documents (GRs, invoices, receipts) are never soft-deleted; they are
 * cancelled or reversed instead, so they never appear here.
 */
@RestController
@RequestMapping("/api/v1/recycle-bin")
class RecycleBinController {

    record DeletedItem(UUID id, String label, OffsetDateTime deletedAt, String deletedBy, String deleteReason) {
    }

    private final JdbcClient jdbc;

    RecycleBinController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{type}")
    List<DeletedItem> list(@PathVariable String type) {
        TenantContext.requireTenantId();
        RecycleBin bin = RecycleBin.fromPath(type);
        return jdbc.sql("SELECT id, (" + bin.labelExpression + ") AS label, deleted_at, deleted_by, delete_reason"
                        + " FROM " + bin.table + " WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
                .query((rs, n) -> new DeletedItem(Rows.uuid(rs, "id"), rs.getString("label"),
                        Rows.timestamp(rs, "deleted_at"), rs.getString("deleted_by"), rs.getString("delete_reason")))
                .list();
    }

    @PostMapping("/{type}/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void restore(@PathVariable String type, @PathVariable UUID id) {
        TenantContext.requireTenantId();
        RecycleBin bin = RecycleBin.fromPath(type);
        int updated = jdbc.sql("UPDATE " + bin.table
                        + " SET deleted_at = NULL, deleted_by = NULL, delete_reason = NULL"
                        + " WHERE id = :id AND deleted_at IS NOT NULL")
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new NotFoundException("Deleted record", id);
        }
    }

    /**
     * Permanently deletes a record that is already in the recycle bin.
     * TODO: require a second approver (maker-checker) once login and roles are enforced.
     */
    @DeleteMapping("/{type}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void purge(@PathVariable String type, @PathVariable UUID id, @RequestParam boolean confirm) {
        UUID tenantId = TenantContext.requireTenantId();
        if (!confirm) {
            throw new IllegalArgumentException("Permanent deletion needs confirm=true.");
        }
        RecycleBin bin = RecycleBin.fromPath(type);
        boolean onHold = jdbc.sql("SELECT EXISTS (SELECT 1 FROM legal_hold WHERE tenant_id = :tenant"
                        + " AND table_name = :table AND record_id = :id AND released_at IS NULL)")
                .param("tenant", tenantId)
                .param("table", bin.table)
                .param("id", id)
                .query(Boolean.class)
                .single();
        if (onHold) {
            throw new BusinessRuleException("This record is under a legal hold and cannot be permanently deleted.");
        }
        int deleted = jdbc.sql("DELETE FROM " + bin.table + " WHERE id = :id AND deleted_at IS NOT NULL")
                .param("id", id)
                .update();
        if (deleted == 0) {
            throw new NotFoundException("Deleted record", id);
        }
    }
}

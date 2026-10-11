package com.aadvixon.tms.rate;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.BusinessRuleException;
import com.aadvixon.tms.platform.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * Charge heads added to a booking besides freight: GR charge, hamali, door
 * collection, FOV and so on. Each has a calculation method and a default value;
 * client rate cards can override the value.
 */
@RestController
@RequestMapping("/api/v1/charge-heads")
class ChargeHeadController {

    record ChargeHead(UUID id, String code, String name, String calcMethod, BigDecimal defaultValue,
                      BigDecimal minAmount, boolean isAuto, String editControl, boolean taxable, String taxCode,
                      int sortOrder, boolean isSystem, boolean active) {
    }

    record ChargeHeadRequest(
            @NotBlank @Size(max = 30) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "FIXED|PER_PACKAGE|PER_KG|PERCENT_OF_FREIGHT|PERCENT_OF_VALUE") String calcMethod,
            @DecimalMin("0") BigDecimal defaultValue,
            @DecimalMin("0") BigDecimal minAmount,
            boolean isAuto,
            @Pattern(regexp = "LOCKED|INCREASE_ONLY|FREE") String editControl,
            Boolean taxable,
            @Size(max = 20) String taxCode,
            Integer sortOrder,
            Boolean active) {
    }

    private final JdbcClient jdbc;

    ChargeHeadController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<ChargeHead> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        TenantContext.requireTenantId();
        return jdbc.sql("SELECT * FROM charge_head WHERE deleted_at IS NULL AND (NOT :activeOnly OR active)"
                        + " ORDER BY sort_order, name")
                .param("activeOnly", activeOnly)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Adds any missing default charge heads (freight, GR charge, hamali ...). Existing ones are kept. */
    @PostMapping("/defaults")
    @Transactional
    List<ChargeHead> loadDefaults() {
        TenantContext.requireTenantId();
        jdbc.sql("SELECT provision_charge_heads()").query().singleValue();
        return list(false);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ChargeHead create(@Valid @RequestBody ChargeHeadRequest r) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID id = jdbc.sql("""
                        INSERT INTO charge_head (tenant_id, code, name, calc_method, default_value, min_amount, is_auto,
                                                 edit_control, taxable, tax_code, sort_order, active, created_by)
                        VALUES (:tenant, upper(:code), :name, :method, :value, :min, :auto, :edit, :taxable, :taxCode,
                                :sort, :active, :user)
                        RETURNING id
                        """)
                .param("tenant", tenantId)
                .param("user", TenantContext.currentUserId())
                .param("code", r.code().trim())
                .param("name", r.name().trim())
                .param("method", r.calcMethod())
                .param("value", orZero(r.defaultValue()))
                .param("min", orZero(r.minAmount()))
                .param("auto", r.isAuto())
                .param("edit", r.editControl() == null ? "FREE" : r.editControl())
                .param("taxable", r.taxable() == null || r.taxable())
                .param("taxCode", r.taxCode())
                .param("sort", r.sortOrder() == null ? 100 : r.sortOrder())
                .param("active", r.active() == null || r.active())
                .query(UUID.class)
                .single();
        return find(id);
    }

    @PutMapping("/{id}")
    @Transactional
    ChargeHead update(@PathVariable UUID id, @Valid @RequestBody ChargeHeadRequest r) {
        TenantContext.requireTenantId();
        ChargeHead current = find(id);
        // Freight is worked out from the rate card: its code and method stay fixed.
        String method = current.isSystem() ? current.calcMethod() : r.calcMethod();
        String code = current.isSystem() ? current.code() : r.code().trim();
        jdbc.sql("""
                        UPDATE charge_head
                           SET code = upper(:code), name = :name, calc_method = :method, default_value = :value,
                               min_amount = :min, is_auto = :auto, edit_control = :edit, taxable = :taxable,
                               tax_code = :taxCode, sort_order = :sort, active = :active
                         WHERE id = :id AND deleted_at IS NULL
                        """)
                .param("id", id)
                .param("code", code)
                .param("name", r.name().trim())
                .param("method", method)
                .param("value", orZero(r.defaultValue()))
                .param("min", orZero(r.minAmount()))
                .param("auto", current.isSystem() || r.isAuto())
                .param("edit", r.editControl() == null ? current.editControl() : r.editControl())
                .param("taxable", r.taxable() == null ? current.taxable() : r.taxable())
                .param("taxCode", r.taxCode())
                .param("sort", r.sortOrder() == null ? current.sortOrder() : r.sortOrder())
                .param("active", current.isSystem() || r.active() == null || r.active())
                .update();
        return find(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable UUID id, @RequestParam String reason) {
        TenantContext.requireTenantId();
        if (find(id).isSystem()) {
            throw new BusinessRuleException("The freight charge head cannot be deleted");
        }
        jdbc.sql("UPDATE charge_head SET deleted_at = now(), deleted_by = :user, delete_reason = :reason"
                        + " WHERE id = :id AND deleted_at IS NULL")
                .param("user", TenantContext.currentUserId())
                .param("reason", reason)
                .param("id", id)
                .update();
    }

    private ChargeHead find(UUID id) {
        return jdbc.sql("SELECT * FROM charge_head WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional()
                .orElseThrow(() -> new NotFoundException("Charge head", id));
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static ChargeHead map(ResultSet rs) throws SQLException {
        return new ChargeHead(Rows.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
                rs.getString("calc_method"), Rows.decimal(rs, "default_value"), Rows.decimal(rs, "min_amount"),
                rs.getBoolean("is_auto"), rs.getString("edit_control"), rs.getBoolean("taxable"),
                rs.getString("tax_code"), rs.getInt("sort_order"), rs.getBoolean("is_system"),
                rs.getBoolean("active"));
    }
}

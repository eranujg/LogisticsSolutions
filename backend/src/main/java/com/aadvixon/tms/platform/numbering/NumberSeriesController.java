package com.aadvixon.tms.platform.numbering;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Set up number series per document type, location and financial year. */
@RestController
@RequestMapping("/api/v1/number-series")
class NumberSeriesController {

    record NumberSeries(UUID id, UUID locationId, String documentType, String financialYear, String prefix,
                        int padding, long nextValue, boolean active) {
    }

    record CreateRequest(
            UUID locationId,
            @NotBlank @Pattern(regexp = "[A-Z_]{2,30}") String documentType,
            @NotBlank @Size(max = 10) String financialYear,
            @Size(max = 30) String prefix,
            @Min(1) @Max(12) Integer padding,
            @Min(1) Long startValue) {
    }

    record UpdateRequest(@Size(max = 30) String prefix, Boolean active) {
    }

    private final JdbcClient jdbc;

    NumberSeriesController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<NumberSeries> list() {
        TenantContext.requireTenantId();
        return jdbc.sql("SELECT * FROM number_series ORDER BY document_type, financial_year")
                .query((rs, n) -> map(rs))
                .list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    NumberSeries create(@Valid @RequestBody CreateRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return jdbc.sql("""
                        INSERT INTO number_series (tenant_id, location_id, document_type, financial_year, prefix, padding, next_value)
                        VALUES (:tenant, CAST(:location AS uuid), :type, :fy, :prefix, :padding, :start)
                        RETURNING *
                        """)
                .param("tenant", tenantId)
                .param("location", request.locationId())
                .param("type", request.documentType())
                .param("fy", request.financialYear())
                .param("prefix", request.prefix() == null ? "" : request.prefix())
                .param("padding", request.padding() == null ? 6 : request.padding())
                .param("start", request.startValue() == null ? 1L : request.startValue())
                .query((rs, n) -> map(rs))
                .single();
    }

    /** Only prefix and active can change; the counter can never be moved back. */
    @PatchMapping("/{id}")
    @Transactional
    NumberSeries update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        TenantContext.requireTenantId();
        return jdbc.sql("""
                        UPDATE number_series
                           SET prefix = COALESCE(:prefix, prefix),
                               active = COALESCE(CAST(:active AS boolean), active)
                         WHERE id = :id
                        RETURNING *
                        """)
                .param("prefix", request.prefix())
                .param("active", request.active())
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional()
                .orElseThrow(() -> new NotFoundException("Number series", id));
    }

    private static NumberSeries map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new NumberSeries(Rows.uuid(rs, "id"), Rows.uuid(rs, "location_id"), rs.getString("document_type"),
                rs.getString("financial_year"), rs.getString("prefix"), rs.getInt("padding"),
                rs.getLong("next_value"), rs.getBoolean("active"));
    }
}

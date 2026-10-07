package com.aadvixon.tms.company;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Company setup. Currency and financial year default from the country pack
 * when not supplied.
 */
@RestController
@RequestMapping("/api/v1/companies")
class CompanyController {

    record Company(UUID id, String legalName, String tradeName, String countryCode, String stateCode,
                   List<String> businessTypes, String ownershipType, String baseCurrency, int fyStartMonth,
                   String email, String phone, String website, String addressLine1, String addressLine2,
                   String cityName, String postalCode, String status, OffsetDateTime createdAt,
                   List<TaxRegistrationService.TaxRegistration> taxRegistrations) {
    }

    record CompanyRequest(
            @NotBlank @Size(max = 200) String legalName,
            @Size(max = 200) String tradeName,
            @NotBlank @Pattern(regexp = "IN|CA|US|AU") String countryCode,
            String stateCode,
            @NotEmpty List<@Pattern(regexp = "FLEET_OWNER|TRANSPORTER|BROKER|3PL|SHIPPER_OWN_FLEET|COURIER") String> businessTypes,
            @NotBlank String ownershipType,
            @Pattern(regexp = "[A-Z]{3}") String baseCurrency,
            @Min(1) @Max(12) Integer fyStartMonth,
            String email,
            String phone,
            String website,
            String addressLine1,
            String addressLine2,
            String cityName,
            String postalCode,
            List<@Valid TaxRegistrationService.TaxRegistrationInput> taxRegistrations) {
    }

    private static final String OWNER_TYPE = "COMPANY";

    private final JdbcClient jdbc;
    private final TaxRegistrationService taxRegistrations;

    CompanyController(JdbcClient jdbc, TaxRegistrationService taxRegistrations) {
        this.jdbc = jdbc;
        this.taxRegistrations = taxRegistrations;
    }

    @GetMapping
    List<Company> list() {
        TenantContext.requireTenantId();
        return jdbc.sql("SELECT * FROM company WHERE deleted_at IS NULL ORDER BY legal_name")
                .query((rs, n) -> map(rs, List.of()))
                .list();
    }

    @GetMapping("/{id}")
    Company get(@PathVariable UUID id) {
        TenantContext.requireTenantId();
        return find(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Company create(@Valid @RequestBody CompanyRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        String ownership = validateOwnership(request);
        UUID id = jdbc.sql("""
                        INSERT INTO company (tenant_id, legal_name, trade_name, country_code, state_code, business_types,
                                             ownership_type, base_currency, fy_start_month, email, phone, website,
                                             address_line1, address_line2, city_name, postal_code, created_by)
                        SELECT :tenant, :legalName, :tradeName, cp.code, :state, CAST(:types AS text[]), :ownership,
                               COALESCE(:currency, cp.currency_code), COALESCE(CAST(:fy AS smallint), cp.fy_start_month),
                               :email, :phone, :website, :line1, :line2, :city, :postal, :user
                          FROM country_pack cp WHERE cp.code = :country
                        RETURNING id
                        """)
                .param("tenant", tenantId)
                .param("country", request.countryCode())
                .param("ownership", ownership)
                .param("types", Rows.array(request.businessTypes()))
                .param("currency", request.baseCurrency())
                .param("fy", request.fyStartMonth())
                .param("user", TenantContext.currentUserId())
                .params(fields(request))
                .query(UUID.class)
                .single();
        taxRegistrations.replace(OWNER_TYPE, id, request.taxRegistrations());
        return find(id);
    }

    @PutMapping("/{id}")
    @Transactional
    Company update(@PathVariable UUID id, @Valid @RequestBody CompanyRequest request) {
        TenantContext.requireTenantId();
        String ownership = validateOwnership(request);
        int updated = jdbc.sql("""
                        UPDATE company
                           SET legal_name = :legalName, trade_name = :tradeName, country_code = :country,
                               state_code = :state, business_types = CAST(:types AS text[]), ownership_type = :ownership,
                               base_currency = COALESCE(:currency, base_currency),
                               fy_start_month = COALESCE(CAST(:fy AS smallint), fy_start_month),
                               email = :email, phone = :phone, website = :website, address_line1 = :line1,
                               address_line2 = :line2, city_name = :city, postal_code = :postal
                         WHERE id = :id AND deleted_at IS NULL
                        """)
                .param("id", id)
                .param("country", request.countryCode())
                .param("ownership", ownership)
                .param("types", Rows.array(request.businessTypes()))
                .param("currency", request.baseCurrency())
                .param("fy", request.fyStartMonth())
                .params(fields(request))
                .update();
        if (updated == 0) {
            throw new NotFoundException("Company", id);
        }
        taxRegistrations.replace(OWNER_TYPE, id, request.taxRegistrations());
        return find(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable UUID id, @RequestParam String reason) {
        TenantContext.requireTenantId();
        int updated = jdbc.sql("UPDATE company SET deleted_at = now(), deleted_by = :user, delete_reason = :reason"
                        + " WHERE id = :id AND deleted_at IS NULL")
                .param("user", TenantContext.currentUserId())
                .param("reason", reason)
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new NotFoundException("Company", id);
        }
    }

    private String validateOwnership(CompanyRequest request) {
        boolean allowed = jdbc.sql("SELECT :o = ANY (ownership_types) FROM country_pack WHERE code = :c")
                .param("o", request.ownershipType())
                .param("c", request.countryCode())
                .query(Boolean.class)
                .optional()
                .orElse(false);
        if (!allowed) {
            throw new IllegalArgumentException("Ownership type " + request.ownershipType()
                    + " is not valid for country " + request.countryCode());
        }
        return request.ownershipType();
    }

    private static java.util.Map<String, Object> fields(CompanyRequest r) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("legalName", r.legalName());
        m.put("tradeName", r.tradeName());
        m.put("state", r.stateCode());
        m.put("email", r.email());
        m.put("phone", r.phone());
        m.put("website", r.website());
        m.put("line1", r.addressLine1());
        m.put("line2", r.addressLine2());
        m.put("city", r.cityName());
        m.put("postal", r.postalCode());
        return m;
    }

    private Company find(UUID id) {
        List<TaxRegistrationService.TaxRegistration> taxes = taxRegistrations.list(OWNER_TYPE, id);
        return jdbc.sql("SELECT * FROM company WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query((rs, n) -> map(rs, taxes))
                .optional()
                .orElseThrow(() -> new NotFoundException("Company", id));
    }

    private static Company map(ResultSet rs, List<TaxRegistrationService.TaxRegistration> taxes) throws SQLException {
        return new Company(Rows.uuid(rs, "id"), rs.getString("legal_name"), rs.getString("trade_name"),
                rs.getString("country_code"), rs.getString("state_code"), Rows.strings(rs, "business_types"),
                rs.getString("ownership_type"), rs.getString("base_currency"), rs.getInt("fy_start_month"),
                rs.getString("email"), rs.getString("phone"), rs.getString("website"),
                rs.getString("address_line1"), rs.getString("address_line2"), rs.getString("city_name"),
                rs.getString("postal_code"), rs.getString("status"), Rows.timestamp(rs, "created_at"), taxes);
    }
}

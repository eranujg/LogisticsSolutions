package com.aadvixon.tms.party;

import com.aadvixon.tms.company.TaxRegistrationService;
import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
 * Parties: clients, consignors, consignees and bill-to parties in one master.
 * Quick add needs only a name, a role and a mobile number; the rest can be
 * completed later.
 */
@RestController
@RequestMapping("/api/v1/parties")
class PartyController {

    record Address(UUID id, String addressType, String label, String line1, String line2, UUID cityId,
                   String stateCode, String postalCode, String countryCode, BigDecimal latitude, BigDecimal longitude,
                   String contactName, String contactPhone, String receivingHours, String driverNotes,
                   boolean isDefault) {
    }

    record Party(UUID id, String code, String legalName, String tradeName, String partyKind, List<String> roles,
                 String accountType, String status, String statusReason, String mobile, String email,
                 String website, String industry, String priorityTier, String defaultPaymentType,
                 String billingCycle, BigDecimal creditLimit, Integer creditDays, UUID parentPartyId,
                 UUID homeLocationId, UUID salespersonUserId, String notes, OffsetDateTime createdAt,
                 List<Address> addresses, List<TaxRegistrationService.TaxRegistration> taxRegistrations) {
    }

    record AddressRequest(
            @NotBlank @Pattern(regexp = "BILLING|REGISTERED|PICKUP|DELIVERY|PLANT|WAREHOUSE") String addressType,
            String label,
            @NotBlank String line1,
            String line2,
            UUID cityId,
            String stateCode,
            String postalCode,
            @Pattern(regexp = "IN|CA|US|AU") String countryCode,
            BigDecimal latitude,
            BigDecimal longitude,
            String contactName,
            String contactPhone,
            String receivingHours,
            String driverNotes,
            boolean isDefault) {
    }

    record PartyRequest(
            @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
            @NotBlank @Size(max = 200) String legalName,
            @Size(max = 200) String tradeName,
            @Pattern(regexp = "BUSINESS|INDIVIDUAL") String partyKind,
            @NotEmpty List<@Pattern(regexp = "CONSIGNOR|CONSIGNEE|BILL_TO") String> roles,
            @Pattern(regexp = "CONTRACT|CASH|FORWARDER|GOVERNMENT") String accountType,
            @Pattern(regexp = "PROSPECT|ACTIVE|ON_HOLD|BLOCKED|INACTIVE") String status,
            String statusReason,
            @Pattern(regexp = "\\+?[0-9]{7,15}") String mobile,
            @Email String email,
            String website,
            String industry,
            @Pattern(regexp = "A|B|C") String priorityTier,
            @Pattern(regexp = "PAID|TO_PAY|TBB") String defaultPaymentType,
            @Pattern(regexp = "PER_GR|WEEKLY|FORTNIGHTLY|MONTHLY") String billingCycle,
            @DecimalMin("0") BigDecimal creditLimit,
            @Min(0) @Max(365) Integer creditDays,
            UUID parentPartyId,
            UUID homeLocationId,
            UUID salespersonUserId,
            String notes,
            List<@Valid AddressRequest> addresses,
            List<@Valid TaxRegistrationService.TaxRegistrationInput> taxRegistrations) {
    }

    record DuplicateMatch(UUID id, String code, String legalName, String mobile, String matchedOn) {
    }

    private static final String OWNER_TYPE = "PARTY";

    private final JdbcClient jdbc;
    private final TaxRegistrationService taxRegistrations;

    PartyController(JdbcClient jdbc, TaxRegistrationService taxRegistrations) {
        this.jdbc = jdbc;
        this.taxRegistrations = taxRegistrations;
    }

    /** Search by name, code, mobile or tax number; optionally filter by role and status. */
    @GetMapping
    List<Party> search(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String role,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "50") int limit) {
        TenantContext.requireTenantId();
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return jdbc.sql("""
                        SELECT p.*, p.email::text AS email_text FROM party p
                         WHERE p.deleted_at IS NULL
                           AND (CAST(:role AS text) IS NULL OR :role = ANY (p.roles))
                           AND (CAST(:status AS text) IS NULL OR p.status = :status)
                           AND (CAST(:q AS text) IS NULL
                                OR lower(p.legal_name) LIKE :q OR lower(coalesce(p.trade_name, '')) LIKE :q
                                OR lower(p.code) LIKE :q OR p.mobile LIKE :q
                                OR EXISTS (SELECT 1 FROM tax_registration t WHERE t.owner_type = 'PARTY'
                                           AND t.owner_id = p.id AND lower(t.number) LIKE :q))
                         ORDER BY p.legal_name
                         LIMIT :limit
                        """)
                .param("role", role == null ? null : role.toUpperCase())
                .param("status", status == null ? null : status.toUpperCase())
                .param("q", pattern)
                .param("limit", Math.clamp(limit, 1, 200))
                .query((rs, n) -> map(rs, List.of(), List.of()))
                .list();
    }

    /** Possible duplicates before saving: same mobile, email, tax number or very similar name. */
    @GetMapping("/duplicates")
    List<DuplicateMatch> duplicates(@RequestParam(required = false) String mobile,
                                    @RequestParam(required = false) String email,
                                    @RequestParam(required = false) String taxNumber,
                                    @RequestParam(required = false) String name) {
        TenantContext.requireTenantId();
        return jdbc.sql("""
                        SELECT p.id, p.code, p.legal_name, p.mobile,
                               CASE WHEN CAST(:mobile AS text) IS NOT NULL AND p.mobile = :mobile THEN 'MOBILE'
                                    WHEN CAST(:email AS text) IS NOT NULL AND p.email = CAST(:email AS citext) THEN 'EMAIL'
                                    WHEN CAST(:tax AS text) IS NOT NULL AND EXISTS (
                                         SELECT 1 FROM tax_registration t WHERE t.owner_type = 'PARTY'
                                            AND t.owner_id = p.id AND upper(t.number) = upper(:tax)) THEN 'TAX_NUMBER'
                                    ELSE 'NAME' END AS matched_on
                          FROM party p
                         WHERE p.deleted_at IS NULL
                           AND ((CAST(:mobile AS text) IS NOT NULL AND p.mobile = :mobile)
                             OR (CAST(:email AS text) IS NOT NULL AND p.email = CAST(:email AS citext))
                             OR (CAST(:tax AS text) IS NOT NULL AND EXISTS (
                                   SELECT 1 FROM tax_registration t WHERE t.owner_type = 'PARTY'
                                      AND t.owner_id = p.id AND upper(t.number) = upper(:tax)))
                             OR (CAST(:name AS text) IS NOT NULL
                                 AND regexp_replace(lower(p.legal_name), '[^a-z0-9]', '', 'g')
                                   = regexp_replace(lower(:name), '[^a-z0-9]', '', 'g')))
                         LIMIT 20
                        """)
                .param("mobile", blankToNull(mobile))
                .param("email", blankToNull(email))
                .param("tax", blankToNull(taxNumber))
                .param("name", blankToNull(name))
                .query((rs, n) -> new DuplicateMatch(Rows.uuid(rs, "id"), rs.getString("code"),
                        rs.getString("legal_name"), rs.getString("mobile"), rs.getString("matched_on")))
                .list();
    }

    @GetMapping("/{id}")
    Party get(@PathVariable UUID id) {
        TenantContext.requireTenantId();
        return find(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Party create(@Valid @RequestBody PartyRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID id = jdbc.sql("""
                        INSERT INTO party (tenant_id, code, legal_name, trade_name, party_kind, roles, account_type,
                                           status, status_reason, mobile, email, website, industry, priority_tier,
                                           default_payment_type, billing_cycle, credit_limit, credit_days,
                                           parent_party_id, home_location_id, salesperson_user_id, notes, created_by)
                        VALUES (:tenant,
                                COALESCE(upper(:code), 'P' || lpad(nextval('party_code_seq')::text, 6, '0')),
                                :legalName, :tradeName, COALESCE(:kind, 'BUSINESS'), CAST(:roles AS text[]),
                                COALESCE(:accountType, 'CASH'), COALESCE(:status, 'ACTIVE'), :statusReason, :mobile,
                                CAST(:email AS citext), :website, :industry, :tier, :paymentType, :billingCycle,
                                :creditLimit, :creditDays, CAST(:parent AS uuid), CAST(:home AS uuid),
                                CAST(:salesperson AS uuid), :notes, :user)
                        RETURNING id
                        """)
                .param("tenant", tenantId)
                .param("user", TenantContext.currentUserId())
                .params(fields(request))
                .query(UUID.class)
                .single();
        replaceAddresses(tenantId, id, request.addresses());
        taxRegistrations.replace(OWNER_TYPE, id, request.taxRegistrations());
        return find(id);
    }

    @PutMapping("/{id}")
    @Transactional
    Party update(@PathVariable UUID id, @Valid @RequestBody PartyRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        int updated = jdbc.sql("""
                        UPDATE party
                           SET code = COALESCE(upper(:code), code), legal_name = :legalName, trade_name = :tradeName,
                               party_kind = COALESCE(:kind, party_kind), roles = CAST(:roles AS text[]),
                               account_type = COALESCE(:accountType, account_type), status = COALESCE(:status, status),
                               status_reason = :statusReason, mobile = :mobile, email = CAST(:email AS citext),
                               website = :website, industry = :industry, priority_tier = :tier,
                               default_payment_type = :paymentType, billing_cycle = :billingCycle,
                               credit_limit = :creditLimit, credit_days = :creditDays,
                               parent_party_id = CAST(:parent AS uuid), home_location_id = CAST(:home AS uuid),
                               salesperson_user_id = CAST(:salesperson AS uuid), notes = :notes
                         WHERE id = :id AND deleted_at IS NULL
                        """)
                .param("id", id)
                .params(fields(request))
                .update();
        if (updated == 0) {
            throw new NotFoundException("Party", id);
        }
        replaceAddresses(tenantId, id, request.addresses());
        taxRegistrations.replace(OWNER_TYPE, id, request.taxRegistrations());
        return find(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable UUID id, @RequestParam String reason) {
        TenantContext.requireTenantId();
        int updated = jdbc.sql("UPDATE party SET deleted_at = now(), deleted_by = :user, delete_reason = :reason"
                        + " WHERE id = :id AND deleted_at IS NULL")
                .param("user", TenantContext.currentUserId())
                .param("reason", reason)
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new NotFoundException("Party", id);
        }
    }

    private void replaceAddresses(UUID tenantId, UUID partyId, List<AddressRequest> addresses) {
        if (addresses == null) {
            return;
        }
        jdbc.sql("DELETE FROM party_address WHERE party_id = :id").param("id", partyId).update();
        for (AddressRequest a : addresses) {
            jdbc.sql("""
                            INSERT INTO party_address (tenant_id, party_id, address_type, label, line1, line2, city_id,
                                   state_code, postal_code, country_code, latitude, longitude, contact_name,
                                   contact_phone, receiving_hours, driver_notes, is_default)
                            VALUES (:tenant, :party, :type, :label, :line1, :line2, CAST(:city AS uuid), :state,
                                    :postal, :country, :lat, :lng, :contact, :phone, :hours, :notes, :isDefault)
                            """)
                    .param("tenant", tenantId)
                    .param("party", partyId)
                    .param("type", a.addressType())
                    .param("label", a.label())
                    .param("line1", a.line1())
                    .param("line2", a.line2())
                    .param("city", a.cityId())
                    .param("state", a.stateCode())
                    .param("postal", a.postalCode())
                    .param("country", a.countryCode())
                    .param("lat", a.latitude())
                    .param("lng", a.longitude())
                    .param("contact", a.contactName())
                    .param("phone", a.contactPhone())
                    .param("hours", a.receivingHours())
                    .param("notes", a.driverNotes())
                    .param("isDefault", a.isDefault())
                    .update();
        }
    }

    private static Map<String, Object> fields(PartyRequest r) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", blankToNull(r.code()));
        m.put("legalName", r.legalName().trim());
        m.put("tradeName", r.tradeName());
        m.put("kind", r.partyKind());
        m.put("roles", Rows.array(r.roles()));
        m.put("accountType", r.accountType());
        m.put("status", r.status());
        m.put("statusReason", r.statusReason());
        m.put("mobile", blankToNull(r.mobile()));
        m.put("email", blankToNull(r.email()));
        m.put("website", r.website());
        m.put("industry", r.industry());
        m.put("tier", r.priorityTier());
        m.put("paymentType", r.defaultPaymentType());
        m.put("billingCycle", r.billingCycle());
        m.put("creditLimit", r.creditLimit());
        m.put("creditDays", r.creditDays());
        m.put("parent", r.parentPartyId());
        m.put("home", r.homeLocationId());
        m.put("salesperson", r.salespersonUserId());
        m.put("notes", r.notes());
        return m;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Party find(UUID id) {
        List<Address> addresses = jdbc.sql("SELECT * FROM party_address WHERE party_id = :id ORDER BY address_type, is_default DESC")
                .param("id", id)
                .query((rs, n) -> new Address(Rows.uuid(rs, "id"), rs.getString("address_type"), rs.getString("label"),
                        rs.getString("line1"), rs.getString("line2"), Rows.uuid(rs, "city_id"),
                        rs.getString("state_code"), rs.getString("postal_code"), rs.getString("country_code"),
                        Rows.decimal(rs, "latitude"), Rows.decimal(rs, "longitude"), rs.getString("contact_name"),
                        rs.getString("contact_phone"), rs.getString("receiving_hours"), rs.getString("driver_notes"),
                        rs.getBoolean("is_default")))
                .list();
        List<TaxRegistrationService.TaxRegistration> taxes = taxRegistrations.list(OWNER_TYPE, id);
        return jdbc.sql("SELECT p.*, p.email::text AS email_text FROM party p WHERE p.id = :id AND p.deleted_at IS NULL")
                .param("id", id)
                .query((rs, n) -> map(rs, addresses, taxes))
                .optional()
                .orElseThrow(() -> new NotFoundException("Party", id));
    }

    private static Party map(ResultSet rs, List<Address> addresses,
                             List<TaxRegistrationService.TaxRegistration> taxes) throws SQLException {
        return new Party(Rows.uuid(rs, "id"), rs.getString("code"), rs.getString("legal_name"),
                rs.getString("trade_name"), rs.getString("party_kind"), Rows.strings(rs, "roles"),
                rs.getString("account_type"), rs.getString("status"), rs.getString("status_reason"),
                rs.getString("mobile"), rs.getString("email_text"), rs.getString("website"),
                rs.getString("industry"), rs.getString("priority_tier"), rs.getString("default_payment_type"),
                rs.getString("billing_cycle"), Rows.decimal(rs, "credit_limit"), Rows.integer(rs, "credit_days"),
                Rows.uuid(rs, "parent_party_id"), Rows.uuid(rs, "home_location_id"),
                Rows.uuid(rs, "salesperson_user_id"), rs.getString("notes"), Rows.timestamp(rs, "created_at"),
                addresses, taxes);
    }
}

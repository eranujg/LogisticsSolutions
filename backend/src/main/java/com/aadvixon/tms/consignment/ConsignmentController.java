package com.aadvixon.tms.consignment;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.numbering.NumberSeriesService;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.BusinessRuleException;
import com.aadvixon.tms.platform.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
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
 * Consignment booking (GR / bilty / LR). Numbers come from the booking office's
 * series for the financial year, created automatically on first use
 * (e.g. LDH/2627/00001). Pre-printed book numbers can be entered instead.
 * Edits are allowed until the consignment is loaded; after that it can only be
 * moved on by challans and delivery. Cancelling keeps the number with a reason.
 */
@RestController
@RequestMapping("/api/v1/consignments")
class ConsignmentController {

    record Summary(UUID id, String cnNo, LocalDate cnDate, String status, String paymentType, String service,
                   UUID bookingLocationId, String bookingLocationCode, String originCity, String destinationCity,
                   String consignorName, String consigneeName, String billToName, int totalPackages,
                   BigDecimal chargeableWeightKg, BigDecimal total, String currency, OffsetDateTime createdAt) {
    }

    record PackageView(UUID id, int packages, String packageType, String saidToContain, String hsnCode,
                       BigDecimal actualWeightKg, BigDecimal volumeCft, BigDecimal value) {
    }

    record ChargeView(UUID chargeHeadId, String code, String name, BigDecimal quotedAmount, BigDecimal amount,
                      boolean taxable) {
    }

    record EventView(String status, UUID locationId, String note, OffsetDateTime occurredAt, String userId) {
    }

    record Consignment(Summary summary, String financialYear, String creation, String manualBookNo,
                       UUID deliveryLocationId, String deliveryLocationCode, UUID originCityId,
                       UUID destinationCityId, String movementType, String pickupType, String deliveryType,
                       String vehicleType, LocalDate expectedDeliveryDate, UUID consignorId, String consignorTaxId,
                       String consignorMobile, UUID consigneeId, String consigneeTaxId, String consigneeMobile,
                       UUID billToId, BigDecimal actualWeightKg, BigDecimal volumeCft, BigDecimal declaredValue,
                       List<String> invoiceNumbers, String ewayBillNo, LocalDate ewayBillValidUntil, String risk,
                       String privateMarks, String instructions, String rateSource, UUID rateCardId, String rateBasis,
                       BigDecimal rate, BigDecimal quotedFreight, BigDecimal freight, BigDecimal chargesTotal,
                       BigDecimal discount, BigDecimal taxableAmount, String taxPaidBy, BigDecimal taxRate,
                       BigDecimal taxAmount, String overrideReason, String approvedBy, String cancelReason,
                       OffsetDateTime cancelledAt, String cancelledBy, List<PackageView> packages,
                       List<ChargeView> charges, List<EventView> events) {
    }

    record Office(UUID id, String code, String name, UUID cityId, String cityName, String companyName,
                  String countryCode, String currency, String consignmentNoteName) {
    }

    record CancelRequest(@NotBlank @Size(max = 300) String reason) {
    }

    private record PartyInfo(UUID id, String name, String status, String mobile, String taxId) {
    }

    private final JdbcClient jdbc;
    private final ConsignmentPricing pricing;
    private final NumberSeriesService numbers;

    ConsignmentController(JdbcClient jdbc, ConsignmentPricing pricing, NumberSeriesService numbers) {
        this.jdbc = jdbc;
        this.pricing = pricing;
        this.numbers = numbers;
    }

    private static final String SUMMARY_SQL = """
            SELECT c.*, l.code AS booking_location_code, oc.name AS origin_city, dc.name AS destination_city,
                   dl.code AS delivery_location_code
              FROM consignment c
              JOIN location l ON l.id = c.booking_location_id
              LEFT JOIN location dl ON dl.id = c.delivery_location_id
              JOIN city oc ON oc.id = c.origin_city_id
              JOIN city dc ON dc.id = c.destination_city_id
            """;

    /** Search by number, party name, e-way bill or invoice number; filter by dates, office, status and payment. */
    @GetMapping
    List<Summary> search(@RequestParam(required = false) String q,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false) String paymentType,
                         @RequestParam(required = false) UUID locationId,
                         @RequestParam(required = false) UUID partyId,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(defaultValue = "100") int limit) {
        TenantContext.requireTenantId();
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return jdbc.sql(SUMMARY_SQL + """
                         WHERE (CAST(:q AS text) IS NULL OR lower(c.cn_no) LIKE :q OR lower(c.consignor_name) LIKE :q
                                OR lower(c.consignee_name) LIKE :q OR lower(c.bill_to_name) LIKE :q
                                OR c.eway_bill_no LIKE :q OR lower(array_to_string(c.invoice_numbers, ' ')) LIKE :q)
                           AND (CAST(:status AS text) IS NULL OR c.status = :status)
                           AND (CAST(:payment AS text) IS NULL OR c.payment_type = :payment)
                           AND (CAST(:location AS uuid) IS NULL OR c.booking_location_id = CAST(:location AS uuid))
                           AND (CAST(:party AS uuid) IS NULL OR CAST(:party AS uuid) IN (c.consignor_id, c.consignee_id, c.bill_to_id))
                           AND (CAST(:from AS date) IS NULL OR c.cn_date >= CAST(:from AS date))
                           AND (CAST(:to AS date) IS NULL OR c.cn_date <= CAST(:to AS date))
                         ORDER BY c.cn_date DESC, c.created_at DESC
                         LIMIT :limit
                        """)
                .param("q", pattern)
                .param("status", blankToNull(status))
                .param("payment", blankToNull(paymentType))
                .param("location", locationId)
                .param("party", partyId)
                .param("from", from)
                .param("to", to)
                .param("limit", Math.clamp(limit, 1, 500))
                .query((rs, n) -> summary(rs))
                .list();
    }

    /**
     * Offices where consignments can be booked (booking-type offices, or all active
     * offices when none is marked), with the company's country settings. Available to
     * anyone who can view consignments, so clerks do not need the locations master.
     */
    @GetMapping("/offices")
    List<Office> offices() {
        TenantContext.requireTenantId();
        return jdbc.sql("""
                        SELECT l.id, l.code, l.name, l.city_id, ci.name AS city_name, c.legal_name, c.country_code, c.base_currency,
                               cp.consignment_note_name
                          FROM location l
                          JOIN company c ON c.id = l.company_id
                          JOIN country_pack cp ON cp.code = c.country_code
                          LEFT JOIN city ci ON ci.id = l.city_id
                         WHERE l.deleted_at IS NULL AND l.status = 'ACTIVE'
                           AND (l.types && ARRAY['HEAD_OFFICE', 'BRANCH_OFFICE', 'BOOKING_OFFICE', 'FRANCHISE_AGENCY',
                                                 'COLLECTION_POINT', 'IN_PLANT_OFFICE']::text[]
                                OR NOT EXISTS (SELECT 1 FROM location x WHERE x.deleted_at IS NULL AND x.status = 'ACTIVE'
                                               AND x.types && ARRAY['HEAD_OFFICE', 'BRANCH_OFFICE', 'BOOKING_OFFICE']::text[]))
                         ORDER BY l.code
                        """)
                .query((rs, n) -> new Office(Rows.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
                        Rows.uuid(rs, "city_id"), rs.getString("city_name"), rs.getString("legal_name"),
                        rs.getString("country_code"),
                        rs.getString("base_currency"), rs.getString("consignment_note_name")))
                .list();
    }

    @GetMapping("/{id}")
    Consignment get(@PathVariable UUID id) {
        TenantContext.requireTenantId();
        return find(id);
    }

    /** Prices the booking without saving: freight, charges, tax, total, approvals needed and warnings. */
    @PostMapping("/preview")
    ConsignmentPricing.Pricing preview(@Valid @RequestBody ConsignmentRequest r) {
        TenantContext.requireTenantId();
        return pricing.price(r, pricing.office(r.bookingLocationId()), null);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Consignment book(@Valid @RequestBody ConsignmentRequest r) {
        UUID tenantId = TenantContext.requireTenantId();
        ConsignmentPricing.Office office = pricing.office(r.bookingLocationId());
        LocalDate date = r.cnDate() == null ? LocalDate.now() : r.cnDate();
        if (date.isAfter(LocalDate.now().plusDays(1))) {
            throw new BusinessRuleException("The booking date cannot be in the future");
        }
        Parties parties = parties(r);
        ConsignmentPricing.Pricing priced = pricing.price(r, office, null);
        refuseDuplicateEwayBill(priced);
        String approvedBy = checkApproval(priced, r.overrideReason());
        String fy = numbers.financialYear(date, office.fyStartMonth());

        boolean manual = r.manualNo() != null && !r.manualNo().isBlank();
        String cnNo = manual ? r.manualNo().trim().toUpperCase() : nextNumber(office, fy);

        UUID id;
        try {
            id = jdbc.sql("""
                            INSERT INTO consignment (tenant_id, cn_no, cn_date, financial_year, creation, manual_book_no,
                                   booking_location_id, delivery_location_id, origin_city_id, destination_city_id,
                                   payment_type, movement_type, service, pickup_type, delivery_type, vehicle_type,
                                   expected_delivery_date, consignor_id, consignor_name, consignor_tax_id, consignor_mobile,
                                   consignee_id, consignee_name, consignee_tax_id, consignee_mobile, bill_to_id, bill_to_name,
                                   total_packages, actual_weight_kg, volume_cft, chargeable_weight_kg, declared_value,
                                   invoice_numbers, eway_bill_no, eway_bill_valid_until, risk, private_marks, instructions,
                                   rate_source, rate_card_id, rate_card_line_id, rate_basis, rate, quoted_freight, freight,
                                   charges_total, discount, taxable_amount, tax_paid_by, tax_rate, tax_amount, total,
                                   currency, override_reason, approved_by, created_by)
                            VALUES (:tenant, :cnNo, :cnDate, :fy, :creation, :bookNo, :bookingLoc, CAST(:deliveryLoc AS uuid),
                                    :origin, :destination, :payment, :movement, :service, :pickup, :delivery, :vehicle,
                                    CAST(:expected AS date), :consignor, :consignorName, :consignorTax, :consignorMobile,
                                    :consignee, :consigneeName, :consigneeTax, :consigneeMobile, :billTo, :billToName,
                                    :packages, :weight, :volume, :chargeable, :declared, CAST(:invoices AS text[]),
                                    :eway, CAST(:ewayUntil AS date), :risk, :marks, :instructions, :rateSource,
                                    CAST(:rateCard AS uuid), CAST(:rateLine AS uuid), :rateBasis, :rate, :quoted, :freight,
                                    :chargesTotal, :discount, :taxable, :taxPaidBy, :taxRate, :tax, :total, :currency,
                                    :reason, :approvedBy, :user)
                            RETURNING id
                            """)
                    .params(fields(r, parties, priced))
                    .param("tenant", tenantId)
                    .param("cnNo", cnNo)
                    .param("cnDate", date)
                    .param("fy", fy)
                    .param("creation", manual ? "MANUAL" : "SYSTEM")
                    .param("bookNo", manual ? blankToNull(r.manualBookNo()) : null)
                    .param("bookingLoc", r.bookingLocationId())
                    .param("approvedBy", approvedBy)
                    .param("user", TenantContext.currentUserId())
                    .query(UUID.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleException("Number " + cnNo + " is already used");
        }
        saveLines(tenantId, id, r, priced);
        event(tenantId, id, "BOOKED", r.bookingLocationId(), null);
        return find(id);
    }

    /** Changes a consignment that has not been loaded yet. The number, office and financial year stay. */
    @PutMapping("/{id}")
    @Transactional
    Consignment update(@PathVariable UUID id, @Valid @RequestBody ConsignmentRequest r) {
        UUID tenantId = TenantContext.requireTenantId();
        Consignment current = find(id);
        if (!"BOOKED".equals(current.summary().status())) {
            throw new BusinessRuleException("Only booked consignments that are not loaded yet can be edited");
        }
        if (!current.summary().bookingLocationId().equals(r.bookingLocationId())) {
            throw new BusinessRuleException("The booking office cannot be changed; cancel and book again");
        }
        ConsignmentPricing.Office office = pricing.office(r.bookingLocationId());
        LocalDate date = r.cnDate() == null ? current.summary().cnDate() : r.cnDate();
        if (!numbers.financialYear(date, office.fyStartMonth()).equals(current.financialYear())) {
            throw new BusinessRuleException("The date must stay in financial year " + current.financialYear());
        }
        Parties parties = parties(r);
        ConsignmentPricing.Pricing priced = pricing.price(r, office, id);
        refuseDuplicateEwayBill(priced);
        String approvedBy = checkApproval(priced, r.overrideReason());
        jdbc.sql("""
                        UPDATE consignment
                           SET cn_date = :cnDate, delivery_location_id = CAST(:deliveryLoc AS uuid), origin_city_id = :origin,
                               destination_city_id = :destination, payment_type = :payment, movement_type = :movement,
                               service = :service, pickup_type = :pickup, delivery_type = :delivery, vehicle_type = :vehicle,
                               expected_delivery_date = CAST(:expected AS date), consignor_id = :consignor,
                               consignor_name = :consignorName, consignor_tax_id = :consignorTax,
                               consignor_mobile = :consignorMobile, consignee_id = :consignee,
                               consignee_name = :consigneeName, consignee_tax_id = :consigneeTax,
                               consignee_mobile = :consigneeMobile, bill_to_id = :billTo, bill_to_name = :billToName,
                               total_packages = :packages, actual_weight_kg = :weight, volume_cft = :volume,
                               chargeable_weight_kg = :chargeable, declared_value = :declared,
                               invoice_numbers = CAST(:invoices AS text[]), eway_bill_no = :eway,
                               eway_bill_valid_until = CAST(:ewayUntil AS date), risk = :risk, private_marks = :marks,
                               instructions = :instructions, rate_source = :rateSource, rate_card_id = CAST(:rateCard AS uuid),
                               rate_card_line_id = CAST(:rateLine AS uuid), rate_basis = :rateBasis, rate = :rate,
                               quoted_freight = :quoted, freight = :freight, charges_total = :chargesTotal,
                               discount = :discount, taxable_amount = :taxable, tax_paid_by = :taxPaidBy,
                               tax_rate = :taxRate, tax_amount = :tax, total = :total, currency = :currency,
                               override_reason = :reason, approved_by = :approvedBy
                         WHERE id = :id AND status = 'BOOKED'
                        """)
                .params(fields(r, parties, priced))
                .param("cnDate", date)
                .param("approvedBy", approvedBy)
                .param("id", id)
                .update();
        jdbc.sql("DELETE FROM consignment_package WHERE consignment_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM consignment_charge WHERE consignment_id = :id").param("id", id).update();
        saveLines(tenantId, id, r, priced);
        event(tenantId, id, "EDITED", r.bookingLocationId(), null);
        return find(id);
    }

    /** Cancels a consignment that has not been loaded; the number is kept and marked cancelled. */
    @PostMapping("/{id}/cancel")
    @Transactional
    Consignment cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest r) {
        UUID tenantId = TenantContext.requireTenantId();
        Consignment current = find(id);
        if (!"BOOKED".equals(current.summary().status())) {
            throw new BusinessRuleException("Only consignments that are not loaded yet can be cancelled");
        }
        jdbc.sql("""
                        UPDATE consignment SET status = 'CANCELLED', cancelled_at = now(), cancelled_by = :user,
                               cancel_reason = :reason
                         WHERE id = :id AND status = 'BOOKED'
                        """)
                .param("user", TenantContext.currentUserId())
                .param("reason", r.reason().trim())
                .param("id", id)
                .update();
        event(tenantId, id, "CANCELLED", current.summary().bookingLocationId(), r.reason().trim());
        return find(id);
    }

    // ------------------------------------------------------------------

    private record Parties(PartyInfo consignor, PartyInfo consignee, PartyInfo billTo) {
    }

    private Parties parties(ConsignmentRequest r) {
        UUID billToId = ConsignmentPricing.billTo(r);
        PartyInfo consignor = party(r.consignorId(), "Consignor");
        PartyInfo consignee = party(r.consigneeId(), "Consignee");
        PartyInfo billTo = party(billToId, "Bill-to party");
        for (PartyInfo p : List.of(consignor, consignee, billTo)) {
            if ("BLOCKED".equals(p.status()) || "INACTIVE".equals(p.status())) {
                throw new BusinessRuleException(p.name() + " is " + p.status().toLowerCase() + " and cannot be booked");
            }
        }
        if ("TBB".equals(r.paymentType()) && "ON_HOLD".equals(billTo.status())) {
            throw new BusinessRuleException(billTo.name() + " is on credit hold; book as paid or to-pay, or ask accounts");
        }
        return new Parties(consignor, consignee, billTo);
    }

    private PartyInfo party(UUID id, String role) {
        return jdbc.sql("""
                        SELECT p.id, p.legal_name, p.status, p.mobile,
                               (SELECT t.number FROM tax_registration t WHERE t.owner_type = 'PARTY' AND t.owner_id = p.id
                                 ORDER BY t.tax_type = 'GSTIN' DESC, t.created_at LIMIT 1) AS tax_id
                          FROM party p WHERE p.id = :id AND p.deleted_at IS NULL
                        """)
                .param("id", id)
                .query((rs, n) -> new PartyInfo(Rows.uuid(rs, "id"), rs.getString("legal_name"), rs.getString("status"),
                        rs.getString("mobile"), rs.getString("tax_id")))
                .optional()
                .orElseThrow(() -> new BusinessRuleException(role + " not found"));
    }

    /** A duplicate e-way bill is only a warning in the preview; saving it is refused. */
    private static void refuseDuplicateEwayBill(ConsignmentPricing.Pricing priced) {
        priced.warnings().stream().filter(w -> w.startsWith("E-way bill ") && w.contains(" is already on "))
                .findFirst()
                .ifPresent(w -> {
                    throw new BusinessRuleException(w);
                });
    }

    /** Overrides need gr.approve and a reason; returns who approved, or null when nothing needed approval. */
    private static String checkApproval(ConsignmentPricing.Pricing priced, String reason) {
        if (!priced.needsApproval()) {
            return null;
        }
        boolean canApprove = TenantContext.current().map(s -> s.hasPermission("gr.approve")).orElse(false);
        if (!canApprove) {
            throw new BusinessRuleException("Needs approval: " + String.join("; ", priced.approvalReasons()));
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("Give a reason for: " + String.join("; ", priced.approvalReasons()));
        }
        return TenantContext.currentUserId();
    }

    /** Next number from the office's series for the year; the series is created on first use. */
    private String nextNumber(ConsignmentPricing.Office office, String fy) {
        String shortYear = fy.contains("-") ? fy.substring(2, 4) + fy.substring(fy.indexOf('-') + 1) : fy.substring(2);
        jdbc.sql("""
                        INSERT INTO number_series (tenant_id, location_id, document_type, financial_year, prefix, padding)
                        VALUES (app_current_tenant(), :location, 'GR', :fy, :prefix, 5)
                        ON CONFLICT DO NOTHING
                        """)
                .param("location", office.locationId())
                .param("fy", fy)
                .param("prefix", office.locationCode() + "/" + shortYear + "/")
                .update();
        return numbers.next("GR", office.locationId(), fy);
    }

    private Map<String, Object> fields(ConsignmentRequest r, Parties parties, ConsignmentPricing.Pricing p) {
        Map<String, Object> m = new HashMap<>();
        m.put("deliveryLoc", r.deliveryLocationId());
        m.put("origin", r.originCityId());
        m.put("destination", r.destinationCityId());
        m.put("payment", r.paymentType());
        m.put("movement", r.movementType() == null ? "DIRECT" : r.movementType());
        m.put("service", r.service() == null ? "PTL" : r.service());
        m.put("pickup", r.pickupType() == null ? "GODOWN" : r.pickupType());
        m.put("delivery", r.deliveryType() == null ? "GODOWN" : r.deliveryType());
        m.put("vehicle", blankToNull(r.vehicleType()));
        m.put("expected", r.expectedDeliveryDate());
        m.put("consignor", parties.consignor().id());
        m.put("consignorName", parties.consignor().name());
        m.put("consignorTax", parties.consignor().taxId());
        m.put("consignorMobile", parties.consignor().mobile());
        m.put("consignee", parties.consignee().id());
        m.put("consigneeName", parties.consignee().name());
        m.put("consigneeTax", parties.consignee().taxId());
        m.put("consigneeMobile", parties.consignee().mobile());
        m.put("billTo", parties.billTo().id());
        m.put("billToName", parties.billTo().name());
        m.put("packages", p.totalPackages());
        m.put("weight", p.actualWeightKg());
        m.put("volume", p.volumeCft());
        m.put("chargeable", p.chargeableWeightKg());
        m.put("declared", p.declaredValue());
        m.put("invoices", Rows.array(r.invoiceNumbers() == null ? List.of()
                : r.invoiceNumbers().stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList()));
        m.put("eway", blankToNull(r.ewayBillNo()));
        m.put("ewayUntil", r.ewayBillValidUntil());
        m.put("risk", r.risk() == null ? "OWNER" : r.risk());
        m.put("marks", blankToNull(r.privateMarks()));
        m.put("instructions", blankToNull(r.instructions()));
        m.put("rateSource", p.rateSource());
        m.put("rateCard", p.rateCardId());
        m.put("rateLine", p.rateCardLineId());
        m.put("rateBasis", p.rateBasis());
        m.put("rate", p.rate());
        m.put("quoted", p.quotedFreight());
        m.put("freight", p.freight());
        m.put("chargesTotal", p.chargesTotal());
        m.put("discount", p.discount());
        m.put("taxable", p.taxableAmount());
        m.put("taxPaidBy", p.taxPaidBy());
        m.put("taxRate", p.taxRate());
        m.put("tax", p.taxAmount());
        m.put("total", p.total());
        m.put("currency", p.currency());
        m.put("reason", blankToNull(r.overrideReason()));
        return m;
    }

    private void saveLines(UUID tenantId, UUID id, ConsignmentRequest r, ConsignmentPricing.Pricing priced) {
        int sort = 0;
        for (ConsignmentRequest.PackageLine line : r.packages()) {
            jdbc.sql("""
                            INSERT INTO consignment_package (tenant_id, consignment_id, packages, package_type,
                                   said_to_contain, hsn_code, actual_weight_kg, volume_cft, value, sort_order)
                            VALUES (:tenant, :cn, :packages, :type, :stc, :hsn, :weight, :volume, :value, :sort)
                            """)
                    .param("tenant", tenantId)
                    .param("cn", id)
                    .param("packages", line.packages())
                    .param("type", line.packageType().trim().toUpperCase())
                    .param("stc", line.saidToContain().trim())
                    .param("hsn", blankToNull(line.hsnCode()))
                    .param("weight", orZero(line.actualWeightKg()))
                    .param("volume", orZero(line.volumeCft()))
                    .param("value", orZero(line.value()))
                    .param("sort", sort++)
                    .update();
        }
        sort = 0;
        for (ConsignmentPricing.PricedCharge c : priced.charges()) {
            jdbc.sql("""
                            INSERT INTO consignment_charge (tenant_id, consignment_id, charge_head_id, code, name,
                                   quoted_amount, amount, taxable, sort_order)
                            VALUES (:tenant, :cn, :head, :code, :name, :quoted, :amount, :taxable, :sort)
                            """)
                    .param("tenant", tenantId)
                    .param("cn", id)
                    .param("head", c.chargeHeadId())
                    .param("code", c.code())
                    .param("name", c.name())
                    .param("quoted", c.quotedAmount())
                    .param("amount", c.amount())
                    .param("taxable", c.taxable())
                    .param("sort", sort++)
                    .update();
        }
    }

    private void event(UUID tenantId, UUID id, String status, UUID locationId, String note) {
        jdbc.sql("""
                        INSERT INTO consignment_event (tenant_id, consignment_id, status, location_id, note, user_id)
                        VALUES (:tenant, :cn, :status, CAST(:location AS uuid), :note, :user)
                        """)
                .param("tenant", tenantId)
                .param("cn", id)
                .param("status", status)
                .param("location", locationId)
                .param("note", note)
                .param("user", TenantContext.currentUserId())
                .update();
    }

    private Consignment find(UUID id) {
        List<PackageView> packages = jdbc.sql("SELECT * FROM consignment_package WHERE consignment_id = :id ORDER BY sort_order")
                .param("id", id)
                .query((rs, n) -> new PackageView(Rows.uuid(rs, "id"), rs.getInt("packages"), rs.getString("package_type"),
                        rs.getString("said_to_contain"), rs.getString("hsn_code"), Rows.decimal(rs, "actual_weight_kg"),
                        Rows.decimal(rs, "volume_cft"), Rows.decimal(rs, "value")))
                .list();
        List<ChargeView> charges = jdbc.sql("SELECT * FROM consignment_charge WHERE consignment_id = :id ORDER BY sort_order")
                .param("id", id)
                .query((rs, n) -> new ChargeView(Rows.uuid(rs, "charge_head_id"), rs.getString("code"), rs.getString("name"),
                        Rows.decimal(rs, "quoted_amount"), Rows.decimal(rs, "amount"), rs.getBoolean("taxable")))
                .list();
        List<EventView> events = jdbc.sql("SELECT * FROM consignment_event WHERE consignment_id = :id ORDER BY occurred_at, id")
                .param("id", id)
                .query((rs, n) -> new EventView(rs.getString("status"), Rows.uuid(rs, "location_id"), rs.getString("note"),
                        Rows.timestamp(rs, "occurred_at"), rs.getString("user_id")))
                .list();
        return jdbc.sql(SUMMARY_SQL + " WHERE c.id = :id")
                .param("id", id)
                .query((rs, n) -> new Consignment(summary(rs), rs.getString("financial_year"), rs.getString("creation"),
                        rs.getString("manual_book_no"), Rows.uuid(rs, "delivery_location_id"),
                        rs.getString("delivery_location_code"), Rows.uuid(rs, "origin_city_id"),
                        Rows.uuid(rs, "destination_city_id"), rs.getString("movement_type"), rs.getString("pickup_type"),
                        rs.getString("delivery_type"), rs.getString("vehicle_type"), Rows.date(rs, "expected_delivery_date"),
                        Rows.uuid(rs, "consignor_id"), rs.getString("consignor_tax_id"), rs.getString("consignor_mobile"),
                        Rows.uuid(rs, "consignee_id"), rs.getString("consignee_tax_id"), rs.getString("consignee_mobile"),
                        Rows.uuid(rs, "bill_to_id"), Rows.decimal(rs, "actual_weight_kg"), Rows.decimal(rs, "volume_cft"),
                        Rows.decimal(rs, "declared_value"), Rows.strings(rs, "invoice_numbers"), rs.getString("eway_bill_no"),
                        Rows.date(rs, "eway_bill_valid_until"), rs.getString("risk"), rs.getString("private_marks"),
                        rs.getString("instructions"), rs.getString("rate_source"), Rows.uuid(rs, "rate_card_id"),
                        rs.getString("rate_basis"), Rows.decimal(rs, "rate"), Rows.decimal(rs, "quoted_freight"),
                        Rows.decimal(rs, "freight"), Rows.decimal(rs, "charges_total"), Rows.decimal(rs, "discount"),
                        Rows.decimal(rs, "taxable_amount"), rs.getString("tax_paid_by"), Rows.decimal(rs, "tax_rate"),
                        Rows.decimal(rs, "tax_amount"), rs.getString("override_reason"), rs.getString("approved_by"),
                        rs.getString("cancel_reason"), Rows.timestamp(rs, "cancelled_at"), rs.getString("cancelled_by"),
                        packages, charges, events))
                .optional()
                .orElseThrow(() -> new NotFoundException("Consignment", id));
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        return new Summary(Rows.uuid(rs, "id"), rs.getString("cn_no"), Rows.date(rs, "cn_date"), rs.getString("status"),
                rs.getString("payment_type"), rs.getString("service"), Rows.uuid(rs, "booking_location_id"),
                rs.getString("booking_location_code"), rs.getString("origin_city"), rs.getString("destination_city"),
                rs.getString("consignor_name"), rs.getString("consignee_name"), rs.getString("bill_to_name"),
                rs.getInt("total_packages"), Rows.decimal(rs, "chargeable_weight_kg"), Rows.decimal(rs, "total"),
                rs.getString("currency"), Rows.timestamp(rs, "created_at"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

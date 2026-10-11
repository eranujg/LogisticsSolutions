package com.aadvixon.tms.rate;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.security.PermissionDeniedException;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.BusinessRuleException;
import com.aadvixon.tms.platform.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
 * Rate cards: a client's agreed rates, or the standard rates when no client is
 * set. A card is edited while DRAFT, then activated (needs {@code rate_card.approve}).
 * Active rates are locked: to change them, revise the card, which makes a new
 * draft version that replaces the old one from its start date.
 */
@RestController
@RequestMapping("/api/v1/rate-cards")
class RateCardController {

    record Slab(UUID id, BigDecimal fromQty, BigDecimal toQty, BigDecimal rate) {
    }

    record Line(UUID id, UUID originCityId, String originCityName, UUID destinationCityId,
                String destinationCityName, UUID originLocationId, UUID destinationLocationId, String service,
                String vehicleType, String commodity, String paymentType, String rateBasis, BigDecimal rate,
                BigDecimal minCharge, BigDecimal minWeightKg, BigDecimal volumetricKgPerCft, Integer transitDays,
                List<Slab> slabs) {
    }

    record Charge(UUID chargeHeadId, String code, String name, String calcMethod, BigDecimal value,
                  BigDecimal minAmount, boolean isAuto) {
    }

    record Summary(UUID id, String code, int version, String name, UUID partyId, String partyName,
                   String contractRef, String currency, LocalDate validFrom, LocalDate validTo, String status,
                   boolean fallbackToStandard, int lineCount, String approvedBy, OffsetDateTime approvedAt,
                   OffsetDateTime createdAt) {
    }

    record RateCard(Summary card, UUID previousCardId, String notes, List<Line> lines, List<Charge> charges) {
    }

    record SlabRequest(@NotNull @DecimalMin("0") BigDecimal fromQty, @DecimalMin("0") BigDecimal toQty,
                       @NotNull @DecimalMin("0") BigDecimal rate) {
    }

    record LineRequest(
            UUID originCityId,
            UUID destinationCityId,
            UUID originLocationId,
            UUID destinationLocationId,
            @Pattern(regexp = "FTL|PTL|EXPRESS|LOCAL|CONTAINER|ODC") String service,
            @Size(max = 50) String vehicleType,
            @Size(max = 100) String commodity,
            @Pattern(regexp = "PAID|TO_PAY|TBB") String paymentType,
            @NotBlank @Pattern(regexp = "PER_KG|PER_TONNE|PER_PACKAGE|PER_TRIP|PER_KM|PERCENT_OF_VALUE") String rateBasis,
            @NotNull @DecimalMin("0") BigDecimal rate,
            @DecimalMin("0") BigDecimal minCharge,
            @DecimalMin("0") BigDecimal minWeightKg,
            @DecimalMin("0") BigDecimal volumetricKgPerCft,
            @Min(0) @Max(60) Integer transitDays,
            @Valid List<SlabRequest> slabs) {
    }

    record ChargeRequest(@NotNull UUID chargeHeadId, @NotNull @DecimalMin("0") BigDecimal value,
                         @DecimalMin("0") BigDecimal minAmount, Boolean isAuto) {
    }

    record RateCardRequest(
            @NotBlank @Size(max = 150) String name,
            UUID partyId,
            @Size(max = 100) String contractRef,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull LocalDate validFrom,
            LocalDate validTo,
            Boolean fallbackToStandard,
            String notes,
            @Valid List<LineRequest> lines,
            @Valid List<ChargeRequest> charges) {
    }

    record StatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED") String status, String reason) {
    }

    record ReviseRequest(@NotNull LocalDate validFrom) {
    }

    private final JdbcClient jdbc;
    private final RateEngine engine;

    RateCardController(JdbcClient jdbc, RateEngine engine) {
        this.jdbc = jdbc;
        this.engine = engine;
    }

    private static final String SUMMARY_SQL = """
            SELECT rc.*, p.legal_name AS party_name,
                   (SELECT count(*) FROM rate_card_line l WHERE l.rate_card_id = rc.id) AS line_count
              FROM rate_card rc
              LEFT JOIN party p ON p.id = rc.party_id
            """;

    @GetMapping
    List<Summary> list(@RequestParam(required = false) UUID partyId,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "false") boolean standardOnly,
                       @RequestParam(required = false) String q) {
        TenantContext.requireTenantId();
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return jdbc.sql(SUMMARY_SQL + """
                         WHERE rc.deleted_at IS NULL
                           AND (CAST(:party AS uuid) IS NULL OR rc.party_id = CAST(:party AS uuid))
                           AND (NOT :standardOnly OR rc.party_id IS NULL)
                           AND (CAST(:status AS text) IS NULL OR rc.status = :status)
                           AND (CAST(:q AS text) IS NULL OR lower(rc.name) LIKE :q OR lower(rc.code) LIKE :q
                                OR lower(coalesce(p.legal_name, '')) LIKE :q)
                         ORDER BY rc.party_id IS NOT NULL, p.legal_name, rc.code, rc.version DESC
                        """)
                .param("party", partyId)
                .param("standardOnly", standardOnly)
                .param("status", status == null || status.isBlank() ? null : status.toUpperCase())
                .param("q", pattern)
                .query((rs, n) -> summary(rs))
                .list();
    }

    /** Prices a shipment: which card and line apply, chargeable weight, freight and charges. */
    @GetMapping("/lookup")
    RateEngine.RateQuote lookup(@RequestParam(required = false) UUID partyId,
                                @RequestParam UUID originCityId,
                                @RequestParam UUID destinationCityId,
                                @RequestParam(required = false) UUID originLocationId,
                                @RequestParam(required = false) UUID destinationLocationId,
                                @RequestParam(required = false) String service,
                                @RequestParam(required = false) String vehicleType,
                                @RequestParam(required = false) String commodity,
                                @RequestParam(required = false) String paymentType,
                                @RequestParam(required = false) BigDecimal actualWeightKg,
                                @RequestParam(required = false) BigDecimal volumeCft,
                                @RequestParam(required = false) Integer packages,
                                @RequestParam(required = false) BigDecimal declaredValue,
                                @RequestParam(required = false) BigDecimal distanceKm,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return engine.quote(new RateEngine.RateQuery(partyId, originCityId, destinationCityId, originLocationId,
                destinationLocationId, service, vehicleType, commodity, paymentType, actualWeightKg, volumeCft,
                packages, declaredValue, distanceKm, date));
    }

    @GetMapping("/{id}")
    RateCard get(@PathVariable UUID id) {
        TenantContext.requireTenantId();
        return find(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    RateCard create(@Valid @RequestBody RateCardRequest r) {
        UUID tenantId = TenantContext.requireTenantId();
        checkValidity(r.validFrom(), r.validTo());
        UUID id = jdbc.sql("""
                        INSERT INTO rate_card (tenant_id, name, party_id, contract_ref, currency, valid_from, valid_to,
                                               fallback_to_standard, notes, created_by)
                        VALUES (:tenant, :name, CAST(:party AS uuid), :contract, :currency, :from, :to, :fallback,
                                :notes, :user)
                        RETURNING id
                        """)
                .param("tenant", tenantId)
                .param("user", TenantContext.currentUserId())
                .param("name", r.name().trim())
                .param("party", r.partyId())
                .param("contract", r.contractRef())
                .param("currency", currency(r.currency()))
                .param("from", r.validFrom())
                .param("to", r.validTo())
                .param("fallback", r.fallbackToStandard() == null || r.fallbackToStandard())
                .param("notes", r.notes())
                .query(UUID.class)
                .single();
        replaceLines(tenantId, id, r.lines());
        replaceCharges(tenantId, id, r.charges());
        return find(id);
    }

    @PutMapping("/{id}")
    @Transactional
    RateCard update(@PathVariable UUID id, @Valid @RequestBody RateCardRequest r) {
        UUID tenantId = TenantContext.requireTenantId();
        Summary current = find(id).card();
        if (!"DRAFT".equals(current.status())) {
            throw new BusinessRuleException("Only draft rate cards can be edited; revise the card to change its rates");
        }
        checkValidity(r.validFrom(), r.validTo());
        jdbc.sql("""
                        UPDATE rate_card
                           SET name = :name, party_id = CAST(:party AS uuid), contract_ref = :contract,
                               currency = :currency, valid_from = :from, valid_to = :to, fallback_to_standard = :fallback,
                               notes = :notes
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("name", r.name().trim())
                .param("party", current.version() > 1 ? current.partyId() : r.partyId())
                .param("contract", r.contractRef())
                .param("currency", currency(r.currency()))
                .param("from", r.validFrom())
                .param("to", r.validTo())
                .param("fallback", r.fallbackToStandard() == null || r.fallbackToStandard())
                .param("notes", r.notes())
                .update();
        replaceLines(tenantId, id, r.lines());
        replaceCharges(tenantId, id, r.charges());
        return find(id);
    }

    /**
     * Activates (approval) or suspends a card. Activating a revision ends the
     * previous version the day before the revision starts.
     */
    @PutMapping("/{id}/status")
    @Transactional
    RateCard changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        TenantContext.requireTenantId();
        RateCard card = find(id);
        Summary s = card.card();
        if ("ACTIVE".equals(r.status())) {
            boolean canApprove = TenantContext.current().map(scope -> scope.hasPermission("rate_card.approve"))
                    .orElse(false);
            if (!canApprove) {
                throw new PermissionDeniedException("rate_card.approve");
            }
            if (!"DRAFT".equals(s.status()) && !"SUSPENDED".equals(s.status())) {
                throw new BusinessRuleException("Only draft or suspended cards can be activated");
            }
            if (card.lines().isEmpty()) {
                throw new BusinessRuleException("Add at least one rate line before activating");
            }
            if (card.previousCardId() != null) {
                jdbc.sql("""
                                UPDATE rate_card
                                   SET valid_to = CAST(:from AS date) - 1,
                                       status = CASE WHEN CAST(:from AS date) <= current_date THEN 'SUPERSEDED' ELSE status END
                                 WHERE id = :previous AND (valid_to IS NULL OR valid_to >= CAST(:from AS date))
                                """)
                        .param("from", s.validFrom())
                        .param("previous", card.previousCardId())
                        .update();
            }
            String overlap = jdbc.sql("""
                            SELECT code || ' v' || version FROM rate_card
                             WHERE deleted_at IS NULL AND status = 'ACTIVE' AND id <> :id
                               AND party_id IS NOT DISTINCT FROM CAST(:party AS uuid)
                               AND daterange(valid_from, valid_to, '[]') && daterange(CAST(:from AS date), CAST(:to AS date), '[]')
                             LIMIT 1
                            """)
                    .param("id", id)
                    .param("party", s.partyId())
                    .param("from", s.validFrom())
                    .param("to", s.validTo())
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (overlap != null) {
                throw new BusinessRuleException("Rate card " + overlap + " is already active for these dates;"
                        + " revise it or end it first");
            }
            jdbc.sql("UPDATE rate_card SET status = 'ACTIVE', approved_by = :user, approved_at = now() WHERE id = :id")
                    .param("user", TenantContext.currentUserId())
                    .param("id", id)
                    .update();
        } else {
            if (!"ACTIVE".equals(s.status())) {
                throw new BusinessRuleException("Only active cards can be suspended");
            }
            jdbc.sql("UPDATE rate_card SET status = 'SUSPENDED', notes = concat_ws(E'\\n', notes, CAST(:reason AS text)) WHERE id = :id")
                    .param("reason", r.reason() == null ? null : "Suspended: " + r.reason())
                    .param("id", id)
                    .update();
        }
        return find(id);
    }

    /** New draft version with the same lines and charges, starting on the given date. */
    @PostMapping("/{id}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    RateCard revise(@PathVariable UUID id, @Valid @RequestBody ReviseRequest r) {
        TenantContext.requireTenantId();
        Summary s = find(id).card();
        if (!"ACTIVE".equals(s.status()) && !"SUSPENDED".equals(s.status())) {
            throw new BusinessRuleException("Only active or suspended cards can be revised");
        }
        if (!r.validFrom().isAfter(s.validFrom())) {
            throw new BusinessRuleException("The revision must start after " + s.validFrom());
        }
        Integer pending = jdbc.sql("SELECT count(*) FROM rate_card WHERE previous_card_id = :id AND deleted_at IS NULL"
                        + " AND status = 'DRAFT'")
                .param("id", id)
                .query(Integer.class)
                .single();
        if (pending > 0) {
            throw new BusinessRuleException("A draft revision of this card already exists");
        }
        UUID newId = jdbc.sql("""
                        INSERT INTO rate_card (tenant_id, code, name, party_id, contract_ref, currency, valid_from,
                                               valid_to, fallback_to_standard, version, previous_card_id, notes,
                                               created_by)
                        SELECT tenant_id, code, name, party_id, contract_ref, currency, CAST(:from AS date),
                               CASE WHEN valid_to >= CAST(:from AS date) THEN valid_to END, fallback_to_standard,
                               (SELECT max(version) + 1 FROM rate_card x WHERE x.tenant_id = rc.tenant_id
                                   AND upper(x.code) = upper(rc.code)),
                               id, notes, :user
                          FROM rate_card rc WHERE id = :id
                        RETURNING id
                        """)
                .param("from", r.validFrom())
                .param("user", TenantContext.currentUserId())
                .param("id", id)
                .query(UUID.class)
                .single();
        List<UUID> lineIds = jdbc.sql("SELECT id FROM rate_card_line WHERE rate_card_id = :id ORDER BY sort_order")
                .param("id", id)
                .query(UUID.class)
                .list();
        for (UUID lineId : lineIds) {
            UUID copy = jdbc.sql("""
                            INSERT INTO rate_card_line (tenant_id, rate_card_id, origin_city_id, destination_city_id,
                                   origin_location_id, destination_location_id, service, vehicle_type, commodity,
                                   payment_type, rate_basis, rate, min_charge, min_weight_kg, volumetric_kg_per_cft,
                                   transit_days, sort_order)
                            SELECT tenant_id, :card, origin_city_id, destination_city_id, origin_location_id,
                                   destination_location_id, service, vehicle_type, commodity, payment_type, rate_basis,
                                   rate, min_charge, min_weight_kg, volumetric_kg_per_cft, transit_days, sort_order
                              FROM rate_card_line WHERE id = :line
                            RETURNING id
                            """)
                    .param("card", newId)
                    .param("line", lineId)
                    .query(UUID.class)
                    .single();
            jdbc.sql("""
                            INSERT INTO rate_card_slab (tenant_id, rate_card_line_id, from_qty, to_qty, rate)
                            SELECT tenant_id, :copy, from_qty, to_qty, rate FROM rate_card_slab WHERE rate_card_line_id = :line
                            """)
                    .param("copy", copy)
                    .param("line", lineId)
                    .update();
        }
        jdbc.sql("""
                        INSERT INTO rate_card_charge (tenant_id, rate_card_id, charge_head_id, value, min_amount, is_auto)
                        SELECT tenant_id, :card, charge_head_id, value, min_amount, is_auto
                          FROM rate_card_charge WHERE rate_card_id = :id
                        """)
                .param("card", newId)
                .param("id", id)
                .update();
        return find(newId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable UUID id, @RequestParam String reason) {
        TenantContext.requireTenantId();
        if ("ACTIVE".equals(find(id).card().status())) {
            throw new BusinessRuleException("Suspend the rate card before deleting it");
        }
        jdbc.sql("UPDATE rate_card SET deleted_at = now(), deleted_by = :user, delete_reason = :reason"
                        + " WHERE id = :id AND deleted_at IS NULL")
                .param("user", TenantContext.currentUserId())
                .param("reason", reason)
                .param("id", id)
                .update();
    }

    private void replaceLines(UUID tenantId, UUID cardId, List<LineRequest> lines) {
        if (lines == null) {
            return;
        }
        jdbc.sql("DELETE FROM rate_card_line WHERE rate_card_id = :id").param("id", cardId).update();
        int sort = 0;
        for (LineRequest l : lines) {
            checkSlabs(l.slabs());
            UUID lineId = jdbc.sql("""
                            INSERT INTO rate_card_line (tenant_id, rate_card_id, origin_city_id, destination_city_id,
                                   origin_location_id, destination_location_id, service, vehicle_type, commodity,
                                   payment_type, rate_basis, rate, min_charge, min_weight_kg, volumetric_kg_per_cft,
                                   transit_days, sort_order)
                            VALUES (:tenant, :card, CAST(:origin AS uuid), CAST(:destination AS uuid),
                                    CAST(:originLoc AS uuid), CAST(:destLoc AS uuid), :service, :vehicle, :commodity,
                                    :payment, :basis, :rate, :minCharge, :minWeight, :volumetric, :transit, :sort)
                            RETURNING id
                            """)
                    .param("tenant", tenantId)
                    .param("card", cardId)
                    .param("origin", l.originCityId())
                    .param("destination", l.destinationCityId())
                    .param("originLoc", l.originLocationId())
                    .param("destLoc", l.destinationLocationId())
                    .param("service", l.service())
                    .param("vehicle", blankToNull(l.vehicleType()))
                    .param("commodity", blankToNull(l.commodity()))
                    .param("payment", l.paymentType())
                    .param("basis", l.rateBasis())
                    .param("rate", l.rate())
                    .param("minCharge", orZero(l.minCharge()))
                    .param("minWeight", orZero(l.minWeightKg()))
                    .param("volumetric", orZero(l.volumetricKgPerCft()))
                    .param("transit", l.transitDays())
                    .param("sort", sort++)
                    .query(UUID.class)
                    .single();
            if (l.slabs() != null) {
                for (SlabRequest slab : l.slabs()) {
                    jdbc.sql("INSERT INTO rate_card_slab (tenant_id, rate_card_line_id, from_qty, to_qty, rate)"
                                    + " VALUES (:tenant, :line, :from, :to, :rate)")
                            .param("tenant", tenantId)
                            .param("line", lineId)
                            .param("from", slab.fromQty())
                            .param("to", slab.toQty())
                            .param("rate", slab.rate())
                            .update();
                }
            }
        }
    }

    private void replaceCharges(UUID tenantId, UUID cardId, List<ChargeRequest> charges) {
        if (charges == null) {
            return;
        }
        jdbc.sql("DELETE FROM rate_card_charge WHERE rate_card_id = :id").param("id", cardId).update();
        for (ChargeRequest c : charges) {
            jdbc.sql("""
                            INSERT INTO rate_card_charge (tenant_id, rate_card_id, charge_head_id, value, min_amount, is_auto)
                            VALUES (:tenant, :card, :head, :value, :min, :auto)
                            """)
                    .param("tenant", tenantId)
                    .param("card", cardId)
                    .param("head", c.chargeHeadId())
                    .param("value", c.value())
                    .param("min", orZero(c.minAmount()))
                    .param("auto", c.isAuto() == null || c.isAuto())
                    .update();
        }
    }

    private static void checkValidity(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw new BusinessRuleException("Valid to must be on or after valid from");
        }
    }

    /** Slabs must not overlap; an open-ended slab (no upper limit) must be the last one. */
    private static void checkSlabs(List<SlabRequest> slabs) {
        if (slabs == null || slabs.isEmpty()) {
            return;
        }
        List<SlabRequest> sorted = slabs.stream()
                .sorted(Comparator.comparing(SlabRequest::fromQty))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            SlabRequest s = sorted.get(i);
            if (s.toQty() != null && s.toQty().compareTo(s.fromQty()) <= 0) {
                throw new BusinessRuleException("Slab upper limit must be above " + s.fromQty());
            }
            if (i + 1 < sorted.size()) {
                SlabRequest next = sorted.get(i + 1);
                if (s.toQty() == null || s.toQty().compareTo(next.fromQty()) > 0) {
                    throw new BusinessRuleException("Slabs overlap at " + next.fromQty());
                }
            }
        }
    }

    private static String currency(String value) {
        return value == null || value.isBlank() ? "INR" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private RateCard find(UUID id) {
        Summary summary = jdbc.sql(SUMMARY_SQL + " WHERE rc.id = :id AND rc.deleted_at IS NULL")
                .param("id", id)
                .query((rs, n) -> summary(rs))
                .optional()
                .orElseThrow(() -> new NotFoundException("Rate card", id));
        record Extra(UUID previousCardId, String notes) {
        }
        Extra extra = jdbc.sql("SELECT previous_card_id, notes FROM rate_card WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Extra(Rows.uuid(rs, "previous_card_id"), rs.getString("notes")))
                .single();
        List<Line> lines = jdbc.sql("""
                        SELECT l.*, oc.name AS origin_city_name, dc.name AS destination_city_name
                          FROM rate_card_line l
                          LEFT JOIN city oc ON oc.id = l.origin_city_id
                          LEFT JOIN city dc ON dc.id = l.destination_city_id
                         WHERE l.rate_card_id = :id
                         ORDER BY l.sort_order
                        """)
                .param("id", id)
                .query((rs, n) -> new Line(Rows.uuid(rs, "id"), Rows.uuid(rs, "origin_city_id"),
                        rs.getString("origin_city_name"), Rows.uuid(rs, "destination_city_id"),
                        rs.getString("destination_city_name"), Rows.uuid(rs, "origin_location_id"),
                        Rows.uuid(rs, "destination_location_id"), rs.getString("service"),
                        rs.getString("vehicle_type"), rs.getString("commodity"), rs.getString("payment_type"),
                        rs.getString("rate_basis"), Rows.decimal(rs, "rate"), Rows.decimal(rs, "min_charge"),
                        Rows.decimal(rs, "min_weight_kg"), Rows.decimal(rs, "volumetric_kg_per_cft"),
                        Rows.integer(rs, "transit_days"), List.of()))
                .list()
                .stream()
                .map(this::withSlabs)
                .toList();
        List<Charge> charges = jdbc.sql("""
                        SELECT c.*, h.code, h.name, h.calc_method FROM rate_card_charge c
                          JOIN charge_head h ON h.id = c.charge_head_id
                         WHERE c.rate_card_id = :id
                         ORDER BY h.sort_order, h.name
                        """)
                .param("id", id)
                .query((rs, n) -> new Charge(Rows.uuid(rs, "charge_head_id"), rs.getString("code"),
                        rs.getString("name"), rs.getString("calc_method"), Rows.decimal(rs, "value"),
                        Rows.decimal(rs, "min_amount"), rs.getBoolean("is_auto")))
                .list();
        return new RateCard(summary, extra.previousCardId(), extra.notes(), lines, charges);
    }

    private Line withSlabs(Line line) {
        List<Slab> slabs = jdbc.sql("SELECT * FROM rate_card_slab WHERE rate_card_line_id = :id ORDER BY from_qty")
                .param("id", line.id())
                .query((rs, n) -> new Slab(Rows.uuid(rs, "id"), Rows.decimal(rs, "from_qty"),
                        Rows.decimal(rs, "to_qty"), Rows.decimal(rs, "rate")))
                .list();
        return new Line(line.id(), line.originCityId(), line.originCityName(), line.destinationCityId(),
                line.destinationCityName(), line.originLocationId(), line.destinationLocationId(), line.service(),
                line.vehicleType(), line.commodity(), line.paymentType(), line.rateBasis(), line.rate(),
                line.minCharge(), line.minWeightKg(), line.volumetricKgPerCft(), line.transitDays(), slabs);
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        return new Summary(Rows.uuid(rs, "id"), rs.getString("code"), rs.getInt("version"), rs.getString("name"),
                Rows.uuid(rs, "party_id"), rs.getString("party_name"), rs.getString("contract_ref"),
                rs.getString("currency"), Rows.date(rs, "valid_from"), Rows.date(rs, "valid_to"),
                rs.getString("status"), rs.getBoolean("fallback_to_standard"), rs.getInt("line_count"),
                rs.getString("approved_by"), Rows.timestamp(rs, "approved_at"), Rows.timestamp(rs, "created_at"));
    }
}

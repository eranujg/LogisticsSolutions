package com.aadvixon.tms.consignment;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.web.BusinessRuleException;
import com.aadvixon.tms.rate.RateEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Prices a consignment from the rate cards and checks what the clerk changed:
 * freight below the rate, a discount, a locked charge changed, a lane the
 * client card blocks, or a free consignment all need approval
 * ({@code gr.approve}) with a reason.
 */
@Service
class ConsignmentPricing {

    record PricedCharge(UUID chargeHeadId, String code, String name, BigDecimal quotedAmount, BigDecimal amount,
                        String editControl, boolean taxable) {
    }

    record Pricing(String rateSource, UUID rateCardId, String rateCardCode, UUID rateCardLineId, String rateBasis,
                   BigDecimal rate, String rateMessage, int totalPackages, BigDecimal actualWeightKg,
                   BigDecimal volumeCft, BigDecimal chargeableWeightKg, BigDecimal declaredValue,
                   BigDecimal quotedFreight, BigDecimal freight, List<PricedCharge> charges, BigDecimal chargesTotal,
                   BigDecimal discount, BigDecimal taxableAmount, String taxPaidBy, BigDecimal taxRate,
                   BigDecimal taxAmount, BigDecimal total, String currency, Integer transitDays,
                   boolean needsApproval, List<String> approvalReasons, List<String> warnings) {
    }

    /** Company settings that apply to the booking office. */
    record Office(UUID locationId, String locationCode, UUID companyId, String countryCode, String currency,
                  int fyStartMonth) {
    }

    private record Head(UUID id, String code, String name, String calcMethod, String editControl, boolean taxable,
                        boolean active) {
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal EWAY_BILL_LIMIT_INR = BigDecimal.valueOf(50_000);

    private final JdbcClient jdbc;
    private final RateEngine engine;

    ConsignmentPricing(JdbcClient jdbc, RateEngine engine) {
        this.jdbc = jdbc;
        this.engine = engine;
    }

    Office office(UUID locationId) {
        return jdbc.sql("""
                        SELECT l.id, l.code, c.id AS company_id, c.country_code, c.base_currency, c.fy_start_month
                          FROM location l JOIN company c ON c.id = l.company_id
                         WHERE l.id = :id AND l.deleted_at IS NULL AND l.status = 'ACTIVE'
                        """)
                .param("id", locationId)
                .query((rs, n) -> new Office(Rows.uuid(rs, "id"), rs.getString("code"), Rows.uuid(rs, "company_id"),
                        rs.getString("country_code"), rs.getString("base_currency"), rs.getInt("fy_start_month")))
                .optional()
                .orElseThrow(() -> new BusinessRuleException("Booking office not found or not active"));
    }

    /** Who pays: chosen bill-to, else consignor for paid / TBB / FOC and consignee for to-pay. */
    static UUID billTo(ConsignmentRequest r) {
        if (r.billToId() != null) {
            return r.billToId();
        }
        return "TO_PAY".equals(r.paymentType()) ? r.consigneeId() : r.consignorId();
    }

    /** @param self the consignment being edited (its own e-way bill is not a duplicate), or null */
    Pricing price(ConsignmentRequest r, Office office, UUID self) {
        List<String> approval = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        int packages = 0;
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal lineValue = BigDecimal.ZERO;
        for (ConsignmentRequest.PackageLine line : r.packages()) {
            packages += line.packages();
            weight = weight.add(orZero(line.actualWeightKg()));
            volume = volume.add(orZero(line.volumeCft()));
            lineValue = lineValue.add(orZero(line.value()));
        }
        BigDecimal declared = r.declaredValue() != null ? r.declaredValue() : lineValue;

        RateEngine.RateQuote quote = engine.quote(new RateEngine.RateQuery(billTo(r), r.originCityId(),
                r.destinationCityId(), r.bookingLocationId(), r.deliveryLocationId(),
                r.service() == null ? "PTL" : r.service(), r.vehicleType(), null, r.paymentType(), weight, volume,
                packages, declared, null, r.cnDate() == null ? LocalDate.now() : r.cnDate()));

        Map<UUID, Head> heads = heads();
        Head freightHead = heads.values().stream().filter(h -> "RATE_CARD".equals(h.calcMethod())).findFirst()
                .orElse(null);

        // Freight
        BigDecimal quotedFreight = quote.found() ? quote.freight() : null;
        BigDecimal freight;
        if (r.freight() != null) {
            freight = money(r.freight());
        } else if (quotedFreight != null) {
            freight = quotedFreight;
        } else {
            throw new BusinessRuleException(quote.message() == null ? "No rate found; enter the freight"
                    : quote.message() + ". Enter the freight to book.");
        }
        if (quote.blocked()) {
            approval.add("The client's rate card has no rate for this lane");
        }
        if (quotedFreight != null && freight.compareTo(quotedFreight) < 0
                && (freightHead == null || !"FREE".equals(freightHead.editControl()))) {
            approval.add("Freight " + freight + " is below the rate card freight " + quotedFreight);
        }
        if ("FOC".equals(r.paymentType())) {
            approval.add("Free of charge consignment");
        }

        // Charges: the rate card's auto charges, with the clerk's changes and extra charges
        Map<UUID, BigDecimal> requested = new LinkedHashMap<>();
        if (r.charges() != null) {
            for (ConsignmentRequest.ChargeAmount c : r.charges()) {
                requested.put(c.chargeHeadId(), money(c.amount()));
            }
        }
        List<PricedCharge> charges = new ArrayList<>();
        for (RateEngine.ChargeLine q : quote.charges()) {
            BigDecimal amount = r.charges() == null ? q.amount() : requested.getOrDefault(q.chargeHeadId(), BigDecimal.ZERO);
            requested.remove(q.chargeHeadId());
            int cmp = amount.compareTo(q.amount());
            if ("LOCKED".equals(q.editControl()) && cmp != 0) {
                approval.add(q.name() + " is fixed at " + q.amount());
            } else if ("INCREASE_ONLY".equals(q.editControl()) && cmp < 0) {
                approval.add(q.name() + " cannot be below " + q.amount());
            }
            if (amount.signum() > 0 || q.amount().signum() > 0) {
                charges.add(new PricedCharge(q.chargeHeadId(), q.code(), q.name(), q.amount(), amount,
                        q.editControl(), q.taxable()));
            }
        }
        for (Map.Entry<UUID, BigDecimal> extra : requested.entrySet()) {
            Head head = heads.get(extra.getKey());
            if (head == null || !head.active() || "RATE_CARD".equals(head.calcMethod())) {
                throw new BusinessRuleException("Unknown or inactive charge head " + extra.getKey());
            }
            if (extra.getValue().signum() > 0) {
                charges.add(new PricedCharge(head.id(), head.code(), head.name(), null, extra.getValue(),
                        head.editControl(), head.taxable()));
            }
        }

        BigDecimal chargesTotal = charges.stream().map(PricedCharge::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal taxableCharges = charges.stream().filter(PricedCharge::taxable).map(PricedCharge::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = money(orZero(r.discount()));
        if (discount.signum() > 0) {
            approval.add("Discount of " + discount);
        }
        BigDecimal gross = freight.add(chargesTotal);
        if (discount.compareTo(gross) > 0) {
            throw new BusinessRuleException("Discount cannot be more than freight and charges (" + gross + ")");
        }

        // Tax: India GTA is usually paid by the recipient under reverse charge (RCM)
        String taxPaidBy = r.taxPaidBy() != null ? r.taxPaidBy() : "IN".equals(office.countryCode()) ? "RCM" : "TRANSPORTER";
        BigDecimal taxRate = "TRANSPORTER".equals(taxPaidBy) ? orZero(r.taxRate()) : BigDecimal.ZERO;
        BigDecimal taxable = freight.add(taxableCharges).subtract(discount).max(BigDecimal.ZERO);
        BigDecimal tax = money(taxable.multiply(taxRate).divide(HUNDRED, 6, RoundingMode.HALF_UP));
        BigDecimal total = gross.subtract(discount).add(tax);

        if ("IN".equals(office.countryCode()) && declared.compareTo(EWAY_BILL_LIMIT_INR) > 0
                && (r.ewayBillNo() == null || r.ewayBillNo().isBlank())) {
            warnings.add("E-way bill is needed for goods worth more than 50,000");
        }
        if (r.ewayBillNo() != null && !r.ewayBillNo().isBlank()) {
            String used = jdbc.sql("SELECT cn_no FROM consignment WHERE eway_bill_no = :eway AND status <> 'CANCELLED'"
                            + " AND id IS DISTINCT FROM CAST(:self AS uuid) LIMIT 1")
                    .param("eway", r.ewayBillNo())
                    .param("self", self)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (used != null) {
                warnings.add("E-way bill " + r.ewayBillNo() + " is already on " + used);
            }
        }
        if (!quote.found() && quote.message() != null) {
            warnings.add(quote.message());
        }

        return new Pricing(quote.source(), quote.rateCardId(), quote.rateCardCode(), quote.lineId(), quote.rateBasis(),
                quote.rate(), quote.message(), packages, weight, volume,
                quote.found() ? quote.chargeableWeightKg() : weight, declared, quotedFreight, freight,
                List.copyOf(charges), chargesTotal, discount, taxable, taxPaidBy, taxRate, tax, total,
                Objects.requireNonNullElse(office.currency(), "INR"), quote.transitDays(), !approval.isEmpty(),
                List.copyOf(approval), List.copyOf(warnings));
    }

    private Map<UUID, Head> heads() {
        Map<UUID, Head> map = new LinkedHashMap<>();
        jdbc.sql("SELECT id, code, name, calc_method, edit_control, taxable, active FROM charge_head"
                        + " WHERE deleted_at IS NULL ORDER BY sort_order")
                .query((rs, n) -> new Head(Rows.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("calc_method"), rs.getString("edit_control"), rs.getBoolean("taxable"),
                        rs.getBoolean("active")))
                .list()
                .forEach(h -> map.put(h.id(), h));
        return map;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

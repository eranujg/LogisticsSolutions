package com.aadvixon.tms.rate;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.BusinessRuleException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Prices a shipment from the rate cards. Used by the rate lookup API now and by
 * GR booking later, so quotes and bookings always agree.
 *
 * <p>Priority: the client's active card, then the standard rates (a card with no
 * client). Inside a card the most specific matching line wins (exact origin and
 * destination before "any"). A client card can block the fallback to standard
 * rates, in which case a booking needs an approved rate.
 */
@Service
public class RateEngine {

    /** What is being priced. Only origin and destination cities are required. */
    public record RateQuery(UUID partyId, UUID originCityId, UUID destinationCityId, UUID originLocationId,
                            UUID destinationLocationId, String service, String vehicleType, String commodity,
                            String paymentType, BigDecimal actualWeightKg, BigDecimal volumeCft, Integer packages,
                            BigDecimal declaredValue, BigDecimal distanceKm, LocalDate date) {
    }

    public record ChargeLine(UUID chargeHeadId, String code, String name, String calcMethod, BigDecimal value,
                             BigDecimal amount, String editControl, boolean taxable) {
    }

    /**
     * The priced result. {@code source} is CLIENT_CARD, STANDARD or NONE; {@code blocked}
     * means the client card does not allow standard rates for a lane it does not cover.
     */
    public record RateQuote(boolean found, boolean blocked, String message, String source, UUID rateCardId,
                            String rateCardCode, String rateCardName, String currency, UUID lineId, String rateBasis,
                            BigDecimal rate, BigDecimal actualWeightKg, BigDecimal volumetricWeightKg,
                            BigDecimal chargeableWeightKg, BigDecimal quantity, BigDecimal freight,
                            boolean minimumApplied, List<ChargeLine> charges, BigDecimal total, Integer transitDays) {

        static RateQuote none(boolean blocked, String message, String source, Card card) {
            return new RateQuote(false, blocked, message, source, card == null ? null : card.id(),
                    card == null ? null : card.code(), card == null ? null : card.name(),
                    card == null ? null : card.currency(), null, null, null, null, null, null, null, null, false,
                    List.of(), null, null);
        }
    }

    record Card(UUID id, String code, String name, String currency, UUID partyId, boolean fallbackToStandard) {
    }

    private record Line(UUID id, String rateBasis, BigDecimal rate, BigDecimal minCharge, BigDecimal minWeightKg,
                        BigDecimal volumetricKgPerCft, Integer transitDays) {
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private final JdbcClient jdbc;

    RateEngine(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public RateQuote quote(RateQuery q) {
        TenantContext.requireTenantId();
        if (q.originCityId() == null || q.destinationCityId() == null) {
            throw new BusinessRuleException("Origin and destination cities are required");
        }
        LocalDate date = q.date() == null ? LocalDate.now() : q.date();
        List<Card> cards = jdbc.sql("""
                        SELECT id, code, name, currency, party_id, fallback_to_standard FROM rate_card
                         WHERE deleted_at IS NULL AND status = 'ACTIVE'
                           AND :date BETWEEN valid_from AND COALESCE(valid_to, DATE 'infinity')
                           AND (party_id IS NULL OR party_id = CAST(:party AS uuid))
                         ORDER BY party_id IS NULL, version DESC, valid_from DESC
                        """)
                .param("date", date)
                .param("party", q.partyId())
                .query((rs, n) -> new Card(Rows.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("currency"), Rows.uuid(rs, "party_id"), rs.getBoolean("fallback_to_standard")))
                .list();
        Card clientCard = cards.stream().filter(c -> c.partyId() != null).findFirst().orElse(null);
        Card standardCard = cards.stream().filter(c -> c.partyId() == null).findFirst().orElse(null);

        if (clientCard != null) {
            Optional<Line> line = findLine(clientCard.id(), q);
            if (line.isPresent()) {
                return price(q, "CLIENT_CARD", clientCard, line.get(), clientCard);
            }
            if (!clientCard.fallbackToStandard()) {
                return RateQuote.none(true, "No agreed rate for this lane on client card " + clientCard.code()
                        + "; the booking needs an approved rate", "CLIENT_CARD", clientCard);
            }
        }
        if (standardCard != null) {
            Optional<Line> line = findLine(standardCard.id(), q);
            if (line.isPresent()) {
                // Client-specific charge values still apply when freight comes from standard rates.
                return price(q, "STANDARD", standardCard, line.get(), clientCard != null ? clientCard : standardCard);
            }
        }
        return RateQuote.none(false, "No rate found for this lane; enter freight manually", "NONE", null);
    }

    private Optional<Line> findLine(UUID cardId, RateQuery q) {
        return jdbc.sql("""
                        SELECT * FROM rate_card_line
                         WHERE rate_card_id = :card
                           AND (origin_city_id IS NULL OR origin_city_id = :origin)
                           AND (destination_city_id IS NULL OR destination_city_id = :destination)
                           AND (origin_location_id IS NULL OR origin_location_id = CAST(:originLoc AS uuid))
                           AND (destination_location_id IS NULL OR destination_location_id = CAST(:destLoc AS uuid))
                           AND (service IS NULL OR service = CAST(:service AS text))
                           AND (vehicle_type IS NULL OR upper(vehicle_type) = upper(CAST(:vehicle AS text)))
                           AND (commodity IS NULL OR upper(commodity) = upper(CAST(:commodity AS text)))
                           AND (payment_type IS NULL OR payment_type = CAST(:payment AS text))
                         ORDER BY origin_location_id IS NULL, destination_location_id IS NULL,
                                  origin_city_id IS NULL, destination_city_id IS NULL, service IS NULL,
                                  vehicle_type IS NULL, commodity IS NULL, payment_type IS NULL, sort_order
                         LIMIT 1
                        """)
                .param("card", cardId)
                .param("origin", q.originCityId())
                .param("destination", q.destinationCityId())
                .param("originLoc", q.originLocationId())
                .param("destLoc", q.destinationLocationId())
                .param("service", upperOrNull(q.service()))
                .param("vehicle", q.vehicleType())
                .param("commodity", q.commodity())
                .param("payment", upperOrNull(q.paymentType()))
                .query((rs, n) -> new Line(Rows.uuid(rs, "id"), rs.getString("rate_basis"), Rows.decimal(rs, "rate"),
                        Rows.decimal(rs, "min_charge"), Rows.decimal(rs, "min_weight_kg"),
                        Rows.decimal(rs, "volumetric_kg_per_cft"), Rows.integer(rs, "transit_days")))
                .optional();
    }

    private RateQuote price(RateQuery q, String source, Card card, Line line, Card chargeCard) {
        BigDecimal actual = orZero(q.actualWeightKg());
        BigDecimal volumetric = line.volumetricKgPerCft().signum() > 0 && q.volumeCft() != null
                ? q.volumeCft().multiply(line.volumetricKgPerCft()).setScale(3, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal chargeable = actual.max(volumetric).max(line.minWeightKg());
        BigDecimal packages = BigDecimal.valueOf(q.packages() == null ? 0 : q.packages());

        BigDecimal quantity = switch (line.rateBasis()) {
            case "PER_KG" -> chargeable;
            case "PER_TONNE" -> chargeable.divide(THOUSAND, 6, RoundingMode.HALF_UP);
            case "PER_PACKAGE" -> packages;
            case "PER_TRIP" -> BigDecimal.ONE;
            case "PER_KM" -> {
                if (q.distanceKm() == null) {
                    throw new BusinessRuleException("Distance (km) is needed for a per-km rate");
                }
                yield q.distanceKm();
            }
            case "PERCENT_OF_VALUE" -> orZero(q.declaredValue()).divide(HUNDRED, 6, RoundingMode.HALF_UP);
            default -> throw new IllegalStateException("Unknown rate basis " + line.rateBasis());
        };
        BigDecimal slabQty = "PER_PACKAGE".equals(line.rateBasis()) ? packages : chargeable;
        BigDecimal rate = slabRate(line.id(), slabQty).orElse(line.rate());
        BigDecimal computed = money(quantity.multiply(rate));
        boolean minimumApplied = computed.compareTo(line.minCharge()) < 0;
        BigDecimal freight = minimumApplied ? money(line.minCharge()) : computed;

        List<ChargeLine> charges = charges(chargeCard.id(), freight, chargeable, packages, orZero(q.declaredValue()));
        BigDecimal total = charges.stream().map(ChargeLine::amount).reduce(freight, BigDecimal::add);
        return new RateQuote(true, false, null, source, card.id(), card.code(), card.name(), card.currency(),
                line.id(), line.rateBasis(), rate, actual, volumetric, chargeable, quantity, freight, minimumApplied,
                charges, total, line.transitDays());
    }

    private Optional<BigDecimal> slabRate(UUID lineId, BigDecimal qty) {
        return jdbc.sql("""
                        SELECT rate FROM rate_card_slab
                         WHERE rate_card_line_id = :line AND from_qty <= :qty AND (to_qty IS NULL OR :qty < to_qty)
                         ORDER BY from_qty DESC LIMIT 1
                        """)
                .param("line", lineId)
                .param("qty", qty)
                .query(BigDecimal.class)
                .optional();
    }

    /** Auto charge heads plus the card's own charges, each at the card value or the head default. */
    private List<ChargeLine> charges(UUID cardId, BigDecimal freight, BigDecimal chargeable, BigDecimal packages,
                                     BigDecimal declaredValue) {
        record Row(UUID id, String code, String name, String method, BigDecimal value, BigDecimal min,
                   String edit, boolean taxable) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT h.id, h.code, h.name, h.calc_method, h.edit_control, h.taxable,
                               COALESCE(c.value, h.default_value) AS value,
                               COALESCE(c.min_amount, h.min_amount) AS min_amount
                          FROM charge_head h
                          LEFT JOIN rate_card_charge c ON c.charge_head_id = h.id AND c.rate_card_id = :card
                         WHERE h.deleted_at IS NULL AND h.active AND h.calc_method <> 'RATE_CARD'
                           AND (c.charge_head_id IS NOT NULL AND c.is_auto OR c.charge_head_id IS NULL AND h.is_auto)
                         ORDER BY h.sort_order, h.name
                        """)
                .param("card", cardId)
                .query((rs, n) -> new Row(Rows.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("calc_method"), Rows.decimal(rs, "value"), Rows.decimal(rs, "min_amount"),
                        rs.getString("edit_control"), rs.getBoolean("taxable")))
                .list();
        List<ChargeLine> lines = new ArrayList<>();
        for (Row r : rows) {
            BigDecimal amount = switch (r.method()) {
                case "FIXED" -> r.value();
                case "PER_PACKAGE" -> r.value().multiply(packages);
                case "PER_KG" -> r.value().multiply(chargeable);
                case "PERCENT_OF_FREIGHT" -> freight.multiply(r.value()).divide(HUNDRED, 6, RoundingMode.HALF_UP);
                case "PERCENT_OF_VALUE" -> declaredValue.multiply(r.value()).divide(HUNDRED, 6, RoundingMode.HALF_UP);
                default -> BigDecimal.ZERO;
            };
            amount = money(amount.max(r.min()));
            if (amount.signum() > 0) {
                lines.add(new ChargeLine(r.id(), r.code(), r.name(), r.method(), r.value(), amount, r.edit(),
                        r.taxable()));
            }
        }
        return lines;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String upperOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }
}

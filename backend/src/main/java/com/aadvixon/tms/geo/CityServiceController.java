package com.aadvixon.tms.geo;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
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

/** Which cities the tenant books from, delivers to or passes through, and which office serves each. */
@RestController
@RequestMapping("/api/v1/city-services")
class CityServiceController {

    record CityService(UUID id, UUID cityId, String cityName, String stateCode, boolean booking, boolean delivery,
                       boolean transit, UUID bookingLocationId, UUID deliveryLocationId, UUID transitLocationId,
                       boolean doorPickup, boolean doorDelivery, boolean oda, int odaExtraDays,
                       LocalTime bookingCutoff, String status, String suspendedReason, String notes) {
    }

    record CityServiceRequest(
            @NotNull UUID cityId,
            boolean booking,
            boolean delivery,
            boolean transit,
            UUID bookingLocationId,
            UUID deliveryLocationId,
            UUID transitLocationId,
            boolean doorPickup,
            boolean doorDelivery,
            boolean oda,
            @Min(0) Integer odaExtraDays,
            LocalTime bookingCutoff,
            @Pattern(regexp = "ACTIVE|SUSPENDED") String status,
            String suspendedReason,
            String notes) {

        @AssertTrue(message = "choose at least one of booking, delivery or transit")
        boolean isAnyRole() {
            return booking || delivery || transit;
        }
    }

    private static final String SELECT = """
            SELECT cs.*, c.name AS city_name, c.state_code AS city_state
              FROM city_service cs JOIN city c ON c.id = cs.city_id
            """;

    private final JdbcClient jdbc;

    CityServiceController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<CityService> list(@RequestParam(required = false) String role) {
        TenantContext.requireTenantId();
        return jdbc.sql(SELECT + """
                         WHERE cs.deleted_at IS NULL
                           AND (CAST(:role AS text) IS NULL
                                OR (:role = 'BOOKING' AND cs.is_booking)
                                OR (:role = 'DELIVERY' AND cs.is_delivery)
                                OR (:role = 'TRANSIT' AND cs.is_transit))
                         ORDER BY c.name
                        """)
                .param("role", role == null ? null : role.toUpperCase())
                .query((rs, n) -> map(rs))
                .list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    CityService create(@Valid @RequestBody CityServiceRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID id = jdbc.sql("""
                        INSERT INTO city_service (tenant_id, city_id, is_booking, is_delivery, is_transit,
                               booking_location_id, delivery_location_id, transit_location_id, door_pickup,
                               door_delivery, oda, oda_extra_days, booking_cutoff, status, suspended_reason, notes)
                        VALUES (:tenant, :city, :booking, :delivery, :transit, CAST(:bookingLoc AS uuid),
                                CAST(:deliveryLoc AS uuid), CAST(:transitLoc AS uuid), :doorPickup, :doorDelivery,
                                :oda, :odaDays, :cutoff, COALESCE(:status, 'ACTIVE'), :reason, :notes)
                        RETURNING id
                        """)
                .param("tenant", tenantId)
                .params(fields(request))
                .query(UUID.class)
                .single();
        return find(id);
    }

    @PutMapping("/{id}")
    @Transactional
    CityService update(@PathVariable UUID id, @Valid @RequestBody CityServiceRequest request) {
        TenantContext.requireTenantId();
        int updated = jdbc.sql("""
                        UPDATE city_service
                           SET city_id = :city, is_booking = :booking, is_delivery = :delivery, is_transit = :transit,
                               booking_location_id = CAST(:bookingLoc AS uuid),
                               delivery_location_id = CAST(:deliveryLoc AS uuid),
                               transit_location_id = CAST(:transitLoc AS uuid), door_pickup = :doorPickup,
                               door_delivery = :doorDelivery, oda = :oda, oda_extra_days = :odaDays,
                               booking_cutoff = :cutoff, status = COALESCE(:status, status),
                               suspended_reason = :reason, notes = :notes
                         WHERE id = :id AND deleted_at IS NULL
                        """)
                .param("id", id)
                .params(fields(request))
                .update();
        if (updated == 0) {
            throw new NotFoundException("City service", id);
        }
        return find(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable UUID id, @RequestParam String reason) {
        TenantContext.requireTenantId();
        int updated = jdbc.sql("UPDATE city_service SET deleted_at = now(), deleted_by = :user, delete_reason = :reason"
                        + " WHERE id = :id AND deleted_at IS NULL")
                .param("user", TenantContext.currentUserId())
                .param("reason", reason)
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new NotFoundException("City service", id);
        }
    }

    private static Map<String, Object> fields(CityServiceRequest r) {
        Map<String, Object> m = new HashMap<>();
        m.put("city", r.cityId());
        m.put("booking", r.booking());
        m.put("delivery", r.delivery());
        m.put("transit", r.transit());
        m.put("bookingLoc", r.bookingLocationId());
        m.put("deliveryLoc", r.deliveryLocationId());
        m.put("transitLoc", r.transitLocationId());
        m.put("doorPickup", r.doorPickup());
        m.put("doorDelivery", r.doorDelivery());
        m.put("oda", r.oda());
        m.put("odaDays", r.odaExtraDays() == null ? 0 : r.odaExtraDays());
        m.put("cutoff", r.bookingCutoff());
        m.put("status", r.status());
        m.put("reason", r.suspendedReason());
        m.put("notes", r.notes());
        return m;
    }

    private CityService find(UUID id) {
        return jdbc.sql(SELECT + " WHERE cs.id = :id AND cs.deleted_at IS NULL")
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional()
                .orElseThrow(() -> new NotFoundException("City service", id));
    }

    private static CityService map(ResultSet rs) throws SQLException {
        return new CityService(Rows.uuid(rs, "id"), Rows.uuid(rs, "city_id"), rs.getString("city_name"),
                rs.getString("city_state"), rs.getBoolean("is_booking"), rs.getBoolean("is_delivery"),
                rs.getBoolean("is_transit"), Rows.uuid(rs, "booking_location_id"),
                Rows.uuid(rs, "delivery_location_id"), Rows.uuid(rs, "transit_location_id"),
                rs.getBoolean("door_pickup"), rs.getBoolean("door_delivery"), rs.getBoolean("oda"),
                rs.getInt("oda_extra_days"), Rows.time(rs, "booking_cutoff"), rs.getString("status"),
                rs.getString("suspended_reason"), rs.getString("notes"));
    }
}

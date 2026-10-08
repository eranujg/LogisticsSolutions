package com.aadvixon.tms.location;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.BusinessRuleException;
import com.aadvixon.tms.platform.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * Offices and facilities. One location can carry several types, e.g. a head
 * office that is also a booking office and a parking yard.
 */
@RestController
@RequestMapping("/api/v1/locations")
class LocationController {

    static final String TYPES = "REGISTERED_OFFICE|HEAD_OFFICE|REGIONAL_OFFICE|BRANCH_OFFICE|ACCOUNTS_OFFICE|SALES_OFFICE"
            + "|BOOKING_OFFICE|DELIVERY_OFFICE|TRANSIT_HUB|DISPATCH_CENTER|FRANCHISE_AGENCY|COLLECTION_POINT"
            + "|IN_PLANT_OFFICE|BORDER_OFFICE|WAREHOUSE|COLD_STORAGE|CONTAINER_YARD|TRUCK_CARE_CENTER|REPAIR_HUB"
            + "|SPARE_PARTS_STORE|TYRE_SHOP|FUEL_STATION|EV_CHARGING_DEPOT|WEIGHBRIDGE|PARKING_YARD|SCRAP_YARD"
            + "|LABOUR_RESIDENCE|LABOUR_KITCHEN|STAFF_QUARTERS|DRIVER_REST_HOUSE|TRAINING_CENTER";

    record Location(UUID id, UUID companyId, String code, String name, List<String> types, UUID parentId,
                    String status, String tenure, String addressLine1, String addressLine2, UUID cityId,
                    String stateCode, String postalCode, BigDecimal latitude, BigDecimal longitude,
                    Integer geofenceRadiusM, String managerName, String phone, String email, LocalDate openedOn,
                    String costCenterCode) {
    }

    record LocationRequest(
            @NotNull UUID companyId,
            @NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
            @NotBlank @Size(max = 200) String name,
            @NotEmpty List<@Pattern(regexp = TYPES) String> types,
            UUID parentId,
            @Pattern(regexp = "ACTIVE|TEMPORARILY_CLOSED|CLOSED") String status,
            @Pattern(regexp = "OWNED|RENTED|LEASED|FRANCHISE|SHARED") String tenure,
            String addressLine1,
            String addressLine2,
            UUID cityId,
            String stateCode,
            String postalCode,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @Min(10) @Max(50000) Integer geofenceRadiusM,
            String managerName,
            String phone,
            String email,
            LocalDate openedOn,
            String costCenterCode) {
    }

    private final JdbcClient jdbc;

    LocationController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<Location> list(@RequestParam(required = false) String type,
                        @RequestParam(required = false) String status) {
        TenantContext.requireTenantId();
        return jdbc.sql("""
                        SELECT * FROM location
                         WHERE deleted_at IS NULL
                           AND (CAST(:type AS text) IS NULL OR :type = ANY (types))
                           AND (CAST(:status AS text) IS NULL OR status = :status)
                         ORDER BY code
                        """)
                .param("type", type)
                .param("status", status)
                .query((rs, n) -> map(rs))
                .list();
    }

    @GetMapping("/{id}")
    Location get(@PathVariable UUID id) {
        TenantContext.requireTenantId();
        return find(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Location create(@Valid @RequestBody LocationRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID id = jdbc.sql("""
                        INSERT INTO location (tenant_id, company_id, code, name, types, parent_id, status, tenure,
                                              address_line1, address_line2, city_id, state_code, postal_code,
                                              latitude, longitude, geofence_radius_m, manager_name, phone, email,
                                              opened_on, cost_center_code, created_by)
                        VALUES (:tenant, :company, upper(:code), :name, CAST(:types AS text[]), CAST(:parent AS uuid),
                                COALESCE(:status, 'ACTIVE'), :tenure, :line1, :line2, CAST(:city AS uuid), :state,
                                :postal, :lat, :lng, :radius, :manager, :phone, :email, :opened, :costCenter, :user)
                        RETURNING id
                        """)
                .param("tenant", tenantId)
                .param("user", TenantContext.currentUserId())
                .params(fields(request))
                .query(UUID.class)
                .single();
        return find(id);
    }

    @PutMapping("/{id}")
    @Transactional
    Location update(@PathVariable UUID id, @Valid @RequestBody LocationRequest request) {
        TenantContext.requireTenantId();
        if (id.equals(request.parentId())) {
            throw new IllegalArgumentException("A location cannot be its own parent.");
        }
        int updated = jdbc.sql("""
                        UPDATE location
                           SET company_id = :company, code = upper(:code), name = :name, types = CAST(:types AS text[]),
                               parent_id = CAST(:parent AS uuid), status = COALESCE(:status, status), tenure = :tenure,
                               address_line1 = :line1, address_line2 = :line2, city_id = CAST(:city AS uuid),
                               state_code = :state, postal_code = :postal, latitude = :lat, longitude = :lng,
                               geofence_radius_m = :radius, manager_name = :manager, phone = :phone, email = :email,
                               opened_on = :opened, cost_center_code = :costCenter
                         WHERE id = :id AND deleted_at IS NULL
                        """)
                .param("id", id)
                .params(fields(request))
                .update();
        if (updated == 0) {
            throw new NotFoundException("Location", id);
        }
        return find(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable UUID id, @RequestParam String reason) {
        TenantContext.requireTenantId();
        boolean hasChildren = jdbc.sql("SELECT EXISTS (SELECT 1 FROM location WHERE parent_id = :id AND deleted_at IS NULL)")
                .param("id", id)
                .query(Boolean.class)
                .single();
        if (hasChildren) {
            throw new BusinessRuleException("Move or delete the locations under this one first.");
        }
        int updated = jdbc.sql("UPDATE location SET deleted_at = now(), deleted_by = :user, delete_reason = :reason"
                        + " WHERE id = :id AND deleted_at IS NULL")
                .param("user", TenantContext.currentUserId())
                .param("reason", reason)
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new NotFoundException("Location", id);
        }
    }

    private static Map<String, Object> fields(LocationRequest r) {
        Map<String, Object> m = new HashMap<>();
        m.put("company", r.companyId());
        m.put("code", r.code());
        m.put("name", r.name());
        m.put("types", Rows.array(r.types()));
        m.put("parent", r.parentId());
        m.put("status", r.status());
        m.put("tenure", r.tenure());
        m.put("line1", r.addressLine1());
        m.put("line2", r.addressLine2());
        m.put("city", r.cityId());
        m.put("state", r.stateCode());
        m.put("postal", r.postalCode());
        m.put("lat", r.latitude());
        m.put("lng", r.longitude());
        m.put("radius", r.geofenceRadiusM());
        m.put("manager", r.managerName());
        m.put("phone", r.phone());
        m.put("email", r.email());
        m.put("opened", r.openedOn());
        m.put("costCenter", r.costCenterCode());
        return m;
    }

    private Location find(UUID id) {
        return jdbc.sql("SELECT * FROM location WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional()
                .orElseThrow(() -> new NotFoundException("Location", id));
    }

    private static Location map(ResultSet rs) throws SQLException {
        return new Location(Rows.uuid(rs, "id"), Rows.uuid(rs, "company_id"), rs.getString("code"),
                rs.getString("name"), Rows.strings(rs, "types"), Rows.uuid(rs, "parent_id"), rs.getString("status"),
                rs.getString("tenure"), rs.getString("address_line1"), rs.getString("address_line2"),
                Rows.uuid(rs, "city_id"), rs.getString("state_code"), rs.getString("postal_code"),
                Rows.decimal(rs, "latitude"), Rows.decimal(rs, "longitude"), Rows.integer(rs, "geofence_radius_m"),
                rs.getString("manager_name"), rs.getString("phone"), rs.getString("email"),
                Rows.date(rs, "opened_on"), rs.getString("cost_center_code"));
    }
}

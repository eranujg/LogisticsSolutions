package com.aadvixon.tms.geo;

import com.aadvixon.tms.platform.db.Rows;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.web.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
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
 * Cities: a shared global list plus cities a tenant adds itself. Search matches
 * names, old names and aliases (e.g. "Gurgaon" finds Gurugram).
 */
@RestController
@RequestMapping("/api/v1/cities")
class CityController {

    record City(UUID id, boolean global, String countryCode, String stateCode, String name, List<String> aliases,
                String district, String classification, List<String> postalCodes, BigDecimal latitude,
                BigDecimal longitude, String status) {
    }

    record CityRequest(
            @NotBlank @Pattern(regexp = "IN|CA|US|AU") String countryCode,
            @NotBlank String stateCode,
            @NotBlank @Size(max = 120) String name,
            List<@NotBlank String> aliases,
            String district,
            @Pattern(regexp = "METRO|TIER_1|TIER_2|TIER_3|RURAL") String classification,
            List<@NotBlank String> postalCodes,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @Pattern(regexp = "ACTIVE|SUSPENDED|INACTIVE") String status) {
    }

    private final JdbcClient jdbc;

    CityController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<City> search(@RequestParam(required = false) String q,
                      @RequestParam(required = false) String country,
                      @RequestParam(required = false) String state,
                      @RequestParam(defaultValue = "50") int limit) {
        TenantContext.requireTenantId();
        String pattern = q == null || q.isBlank() ? null : q.trim().toLowerCase() + "%";
        return jdbc.sql("""
                        SELECT * FROM city
                         WHERE (CAST(:country AS text) IS NULL OR country_code = :country)
                           AND (CAST(:state AS text) IS NULL OR state_code = :state)
                           AND (CAST(:q AS text) IS NULL
                                OR lower(name) LIKE :q
                                OR EXISTS (SELECT 1 FROM unnest(aliases) a WHERE lower(a) LIKE :q)
                                OR EXISTS (SELECT 1 FROM unnest(postal_codes) p WHERE lower(p) LIKE :q))
                         ORDER BY name
                         LIMIT :limit
                        """)
                .param("country", country == null ? null : country.toUpperCase())
                .param("state", state == null ? null : state.toUpperCase())
                .param("q", pattern)
                .param("limit", Math.clamp(limit, 1, 200))
                .query((rs, n) -> map(rs))
                .list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    City create(@Valid @RequestBody CityRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return jdbc.sql("""
                        INSERT INTO city (tenant_id, country_code, state_code, name, aliases, district, classification,
                                          postal_codes, latitude, longitude, status)
                        VALUES (:tenant, :country, upper(:state), :name, CAST(:aliases AS text[]), :district,
                                :classification, CAST(:postal AS text[]), :lat, :lng, COALESCE(:status, 'ACTIVE'))
                        RETURNING *
                        """)
                .param("tenant", tenantId)
                .param("country", request.countryCode())
                .param("state", request.stateCode())
                .param("name", request.name().trim())
                .param("aliases", Rows.array(request.aliases()))
                .param("district", request.district())
                .param("classification", request.classification())
                .param("postal", Rows.array(request.postalCodes()))
                .param("lat", request.latitude())
                .param("lng", request.longitude())
                .param("status", request.status())
                .query((rs, n) -> map(rs))
                .single();
    }

    /** Only the tenant's own cities can be edited; global cities are read-only. */
    @PutMapping("/{id}")
    @Transactional
    City update(@PathVariable UUID id, @Valid @RequestBody CityRequest request) {
        TenantContext.requireTenantId();
        return jdbc.sql("""
                        UPDATE city
                           SET state_code = upper(:state), name = :name, aliases = CAST(:aliases AS text[]),
                               district = :district, classification = :classification,
                               postal_codes = CAST(:postal AS text[]), latitude = :lat, longitude = :lng,
                               status = COALESCE(:status, status)
                         WHERE id = :id AND tenant_id IS NOT NULL
                        RETURNING *
                        """)
                .param("id", id)
                .param("state", request.stateCode())
                .param("name", request.name().trim())
                .param("aliases", Rows.array(request.aliases()))
                .param("district", request.district())
                .param("classification", request.classification())
                .param("postal", Rows.array(request.postalCodes()))
                .param("lat", request.latitude())
                .param("lng", request.longitude())
                .param("status", request.status())
                .query((rs, n) -> map(rs))
                .optional()
                .orElseThrow(() -> new NotFoundException("City (own cities only)", id));
    }

    private static City map(ResultSet rs) throws SQLException {
        return new City(Rows.uuid(rs, "id"), Rows.uuid(rs, "tenant_id") == null, rs.getString("country_code"),
                rs.getString("state_code"), rs.getString("name"), Rows.strings(rs, "aliases"),
                rs.getString("district"), rs.getString("classification"), Rows.strings(rs, "postal_codes"),
                Rows.decimal(rs, "latitude"), Rows.decimal(rs, "longitude"), rs.getString("status"));
    }
}

package com.aadvixon.tms.company;

import com.aadvixon.tms.platform.db.Rows;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Country packs: defaults for currency, units, financial year, tax IDs and ownership types. */
@RestController
@RequestMapping("/api/v1/country-packs")
class CountryPackController {

    record CountryPack(String code, String name, String currencyCode, String distanceUnit, String weightUnit,
                       String fuelUnit, int fyStartMonth, String dateFormat, String numberGrouping,
                       String consignmentNoteName, List<String> companyTaxIdTypes, List<String> partyTaxIdTypes,
                       List<String> ownershipTypes, String regionLabel) {
    }

    record StateProvince(String code, String name, String kind, String gstStateCode) {
    }

    private final JdbcClient jdbc;

    CountryPackController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<CountryPack> list() {
        return jdbc.sql("SELECT * FROM country_pack ORDER BY name")
                .query((rs, n) -> new CountryPack(rs.getString("code"), rs.getString("name"),
                        rs.getString("currency_code"), rs.getString("distance_unit"), rs.getString("weight_unit"),
                        rs.getString("fuel_unit"), rs.getInt("fy_start_month"), rs.getString("date_format"),
                        rs.getString("number_grouping"), rs.getString("consignment_note_name"),
                        Rows.strings(rs, "company_tax_id_types"), Rows.strings(rs, "party_tax_id_types"),
                        Rows.strings(rs, "ownership_types"), rs.getString("region_label")))
                .list();
    }

    @GetMapping("/{countryCode}/regions")
    List<StateProvince> regions(@PathVariable String countryCode) {
        return jdbc.sql("SELECT code, name, kind, gst_state_code FROM state_province WHERE country_code = :c ORDER BY name")
                .param("c", countryCode.toUpperCase())
                .query((rs, n) -> new StateProvince(rs.getString("code"), rs.getString("name"),
                        rs.getString("kind"), rs.getString("gst_state_code")))
                .list();
    }
}

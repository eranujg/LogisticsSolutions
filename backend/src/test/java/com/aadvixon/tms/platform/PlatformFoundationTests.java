package com.aadvixon.tms.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aadvixon.tms.TestcontainersConfiguration;
import com.aadvixon.tms.platform.numbering.NumberSeriesService;
import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.tenancy.TenantFilter;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * End-to-end checks of the platform foundation against a real PostgreSQL:
 * tenant isolation (RLS), audit log, recycle bin, number series and masters.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"tms.tenancy.header-enabled=true", "tms.platform.admin-api-enabled=true"})
class PlatformFoundationTests {

    @Autowired
    WebApplicationContext context;

    @Autowired
    TenantFilter tenantFilter;

    @Autowired
    NumberSeriesService numberSeries;

    @Autowired
    TransactionTemplate tx;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(tenantFilter).build();
    }

    @Test
    void tenantsAreIsolatedAndChangesAreAuditedAndRecoverable() throws Exception {
        UUID tenantA = createTenant("acme-" + shortId());
        UUID tenantB = createTenant("beta-" + shortId());

        // Company defaults come from the India country pack
        String company = body(mvc.perform(as(tenantA, post("/api/v1/companies")).content("""
                        {"legalName":"Acme Roadways","countryCode":"IN","stateCode":"PB",
                         "businessTypes":["TRANSPORTER","FLEET_OWNER"],"ownershipType":"PARTNERSHIP",
                         "taxRegistrations":[{"taxType":"GSTIN","number":"03aaaca1234b1z5","stateCode":"PB"}]}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseCurrency").value("INR"))
                .andExpect(jsonPath("$.fyStartMonth").value(4))
                .andExpect(jsonPath("$.taxRegistrations[0].number").value("03AAACA1234B1Z5"))
                .andReturn());
        String companyId = JsonPath.read(company, "$.id");

        mvc.perform(as(tenantA, post("/api/v1/locations")).content("""
                        {"companyId":"%s","code":"ldh","name":"Ludhiana HO","types":["HEAD_OFFICE","BOOKING_OFFICE"]}
                        """.formatted(companyId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("LDH"));

        // Quick-add party gets an automatic code
        String party = body(mvc.perform(as(tenantA, post("/api/v1/parties")).content("""
                        {"legalName":"Sharma Traders","roles":["CONSIGNOR"],"mobile":"9876543210"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("P")))
                .andReturn());
        String partyId = JsonPath.read(party, "$.id");

        mvc.perform(as(tenantA, get("/api/v1/parties/duplicates").param("mobile", "9876543210")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].matchedOn").value("MOBILE"));

        // Tenant B cannot see or open tenant A's data
        mvc.perform(as(tenantB, get("/api/v1/parties"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(tenantB, get("/api/v1/parties/" + partyId))).andExpect(status().isNotFound());
        mvc.perform(as(tenantB, get("/api/v1/companies"))).andExpect(jsonPath("$.length()").value(0));

        // No tenant: rejected
        mvc.perform(get("/api/v1/parties")).andExpect(status().isBadRequest());

        // Soft delete -> recycle bin -> restore
        mvc.perform(as(tenantA, delete("/api/v1/parties/" + partyId)).param("reason", "test"))
                .andExpect(status().isNoContent());
        mvc.perform(as(tenantA, get("/api/v1/parties"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(tenantA, get("/api/v1/recycle-bin/parties")))
                .andExpect(jsonPath("$[0].id").value(partyId))
                .andExpect(jsonPath("$[0].deleteReason").value("test"));
        mvc.perform(as(tenantB, get("/api/v1/recycle-bin/parties"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(tenantA, post("/api/v1/recycle-bin/parties/" + partyId + "/restore")))
                .andExpect(status().isNoContent());
        mvc.perform(as(tenantA, get("/api/v1/parties"))).andExpect(jsonPath("$.length()").value(1));

        // Every step is in the audit log, with the user from the request
        mvc.perform(as(tenantA, get("/api/v1/audit")).param("table", "party").param("recordId", partyId))
                .andExpect(jsonPath("$[0].action").value("RESTORE"))
                .andExpect(jsonPath("$[1].action").value("SOFT_DELETE"))
                .andExpect(jsonPath("$[2].action").value("INSERT"))
                .andExpect(jsonPath("$[0].userId").value("tester"));
        mvc.perform(as(tenantB, get("/api/v1/audit")).param("table", "party")).andExpect(jsonPath("$.length()").value(0));

        // Each new tenant gets the default roles
        mvc.perform(as(tenantA, get("/api/v1/roles"))).andExpect(jsonPath("$.length()").value(9));
    }

    @Test
    void invalidMasterDataIsRejected() throws Exception {
        UUID tenant = createTenant("val-" + shortId());

        // Ownership type must belong to the company's country
        mvc.perform(as(tenant, post("/api/v1/companies")).content("""
                        {"legalName":"X","countryCode":"IN","businessTypes":["TRANSPORTER"],"ownershipType":"LLC"}
                        """))
                .andExpect(status().isBadRequest());

        // Party needs at least one valid role
        mvc.perform(as(tenant, post("/api/v1/parties")).content("""
                        {"legalName":"Y","roles":["PILOT"]}
                        """))
                .andExpect(status().isBadRequest());

        // Global cities are visible; editing them is not allowed
        String cities = body(mvc.perform(as(tenant, get("/api/v1/cities")).param("q", "gurgaon"))
                .andExpect(jsonPath("$[0].name").value("Gurugram"))
                .andExpect(jsonPath("$[0].global").value(true))
                .andReturn());
        String cityId = JsonPath.read(cities, "$[0].id");
        mvc.perform(as(tenant, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/cities/" + cityId))
                        .content("""
                                {"countryCode":"IN","stateCode":"HR","name":"Renamed"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void numberSeriesIsGaplessAndTenantScoped() throws Exception {
        UUID tenant = createTenant("num-" + shortId());
        UUID other = createTenant("oth-" + shortId());

        mvc.perform(as(tenant, post("/api/v1/number-series")).content("""
                        {"documentType":"GR","financialYear":"2026-27","prefix":"LDH/","padding":5}
                        """))
                .andExpect(status().isCreated());

        assertThat(next(tenant)).isEqualTo("LDH/00001");

        // A rolled-back booking returns its number to the series
        TenantContext.runAsTenant(tenant, "tester", () -> tx.execute(status -> {
            numberSeries.next("GR", null, "2026-27");
            status.setRollbackOnly();
            return null;
        }));
        assertThat(next(tenant)).isEqualTo("LDH/00002");

        // Another tenant has no access to this series
        assertThatThrownBy(() -> next(other)).hasMessageContaining("No active number series");

        assertThat(numberSeries.financialYear(java.time.LocalDate.of(2026, 3, 31), 4)).isEqualTo("2025-26");
        assertThat(numberSeries.financialYear(java.time.LocalDate.of(2026, 4, 1), 4)).isEqualTo("2026-27");
        assertThat(numberSeries.financialYear(java.time.LocalDate.of(2026, 4, 1), 1)).isEqualTo("2026");
    }

    private String next(UUID tenant) {
        return TenantContext.runAsTenant(tenant, "tester",
                () -> tx.execute(status -> numberSeries.next("GR", null, "2026-27")));
    }

    private UUID createTenant(String code) throws Exception {
        String json = body(mvc.perform(post("/api/v1/platform/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Tenant " + code + "\"}"))
                .andExpect(status().isCreated())
                .andReturn());
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    private static MockHttpServletRequestBuilder as(UUID tenant, MockHttpServletRequestBuilder request) {
        return request.header(TenantFilter.TENANT_HEADER, tenant.toString())
                .header(TenantFilter.USER_HEADER, "tester")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

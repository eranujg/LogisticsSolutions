package com.aadvixon.tms.rate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aadvixon.tms.TestcontainersConfiguration;
import com.aadvixon.tms.platform.tenancy.TenantFilter;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.web.context.WebApplicationContext;

/** Charge heads, rate card lifecycle and the rate lookup used at booking. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"tms.tenancy.header-enabled=true", "tms.platform.admin-api-enabled=true"})
class RateCardTests {

    @Autowired
    WebApplicationContext context;

    @Autowired
    TenantFilter tenantFilter;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(tenantFilter).build();
    }

    @Test
    void ratesAreLookedUpByPriorityWithSlabsMinimumsAndCharges() throws Exception {
        UUID tenant = createTenant("rates-" + shortId());
        UUID other = createTenant("other-" + shortId());
        LocalDate today = LocalDate.now();

        // New tenants get the default charge heads; freight is a fixed system head
        String heads = body(mvc.perform(as(tenant, get("/api/v1/charge-heads")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$[0].code").value("FREIGHT"))
                .andExpect(jsonPath("$[0].isSystem").value(true))
                .andReturn());
        String freightId = first(heads, "$[?(@.code=='FREIGHT')].id");
        String hamaliId = first(heads, "$[?(@.code=='HAMALI')].id");
        mvc.perform(as(tenant, delete("/api/v1/charge-heads/" + freightId)).param("reason", "x"))
                .andExpect(status().isConflict());
        mvc.perform(as(tenant, post("/api/v1/charge-heads/defaults")))
                .andExpect(jsonPath("$.length()").value(11));

        String ludhiana = cityId(tenant, "Ludhiana");
        String mumbai = cityId(tenant, "Mumbai");
        String delhi = cityId(tenant, "New Delhi");

        // Standard rates: Ludhiana -> Mumbai with weight slabs and volumetric weight; any lane as a fallback
        String standard = body(mvc.perform(as(tenant, post("/api/v1/rate-cards")).content("""
                        {"name":"Standard rates","validFrom":"%s","lines":[
                          {"originCityId":"%s","destinationCityId":"%s","rateBasis":"PER_KG","rate":8,
                           "volumetricKgPerCft":6,"transitDays":5,
                           "slabs":[{"fromQty":0,"toQty":100,"rate":10},{"fromQty":100,"rate":7}]},
                          {"rateBasis":"PER_KG","rate":5,"minCharge":300,"minWeightKg":50}]}
                        """.formatted(today.minusDays(30), ludhiana, mumbai)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.card.status").value("DRAFT"))
                .andExpect(jsonPath("$.card.code").value(org.hamcrest.Matchers.startsWith("RC")))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].originCityName").value("Ludhiana"))
                .andExpect(jsonPath("$.lines[0].slabs.length()").value(2))
                .andReturn());
        String standardId = JsonPath.read(standard, "$.card.id");

        // Drafts are not used yet
        lookup(tenant, null, ludhiana, mumbai, "80", null).andExpect(jsonPath("$.source").value("NONE"));
        activate(tenant, standardId).andExpect(status().isOk()).andExpect(jsonPath("$.card.status").value("ACTIVE"));

        // 80 kg actual, 20 CFT x 6 = 120 kg volumetric -> slab 100+ at 7 = 840, plus GR charge 20
        lookup(tenant, null, ludhiana, mumbai, "80", "volumeCft=20")
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.source").value("STANDARD"))
                .andExpect(jsonPath("$.chargeableWeightKg").value(120.0))
                .andExpect(jsonPath("$.rate").value(7.0))
                .andExpect(jsonPath("$.freight").value(840.0))
                .andExpect(jsonPath("$.charges[0].code").value("GR_CHARGE"))
                .andExpect(jsonPath("$.total").value(860.0))
                .andExpect(jsonPath("$.transitDays").value(5));

        // Any-lane line: 10 kg -> minimum 50 kg -> 250, below the minimum charge of 300
        lookup(tenant, null, ludhiana, delhi, "10", null)
                .andExpect(jsonPath("$.chargeableWeightKg").value(50.0))
                .andExpect(jsonPath("$.freight").value(300.0))
                .andExpect(jsonPath("$.minimumApplied").value(true));

        // Client card: own lane rate and hamali per package; no fallback to standard rates
        String party = body(mvc.perform(as(tenant, post("/api/v1/parties")).content("""
                        {"legalName":"Verma Steels","roles":["CONSIGNOR","BILL_TO"],"accountType":"CONTRACT"}
                        """)).andExpect(status().isCreated()).andReturn());
        String partyId = JsonPath.read(party, "$.id");
        String client = body(mvc.perform(as(tenant, post("/api/v1/rate-cards")).content("""
                        {"name":"Verma Steels 2026","partyId":"%s","validFrom":"%s","fallbackToStandard":false,
                         "lines":[{"originCityId":"%s","destinationCityId":"%s","rateBasis":"PER_KG","rate":6}],
                         "charges":[{"chargeHeadId":"%s","value":5}]}
                        """.formatted(partyId, today.minusDays(30), ludhiana, mumbai, hamaliId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.card.partyName").value("Verma Steels"))
                .andExpect(jsonPath("$.charges[0].code").value("HAMALI"))
                .andReturn());
        String clientId = JsonPath.read(client, "$.card.id");
        activate(tenant, clientId).andExpect(status().isOk());

        // 100 kg x 6 = 600, hamali 4 x 5 = 20, GR charge 20
        lookup(tenant, partyId, ludhiana, mumbai, "100", "packages=4")
                .andExpect(jsonPath("$.source").value("CLIENT_CARD"))
                .andExpect(jsonPath("$.rateCardId").value(clientId))
                .andExpect(jsonPath("$.freight").value(600.0))
                .andExpect(jsonPath("$.charges.length()").value(2))
                .andExpect(jsonPath("$.total").value(640.0));

        // Lane not on the client card and fallback disabled: blocked
        lookup(tenant, partyId, ludhiana, delhi, "100", null)
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.blocked").value(true));

        // Active cards are locked; a revision is a new draft version that ends the old one
        mvc.perform(as(tenant, put("/api/v1/rate-cards/" + clientId)).content("""
                        {"name":"x","validFrom":"%s","lines":[]}
                        """.formatted(today))).andExpect(status().isConflict());
        mvc.perform(as(tenant, delete("/api/v1/rate-cards/" + clientId)).param("reason", "x"))
                .andExpect(status().isConflict());
        String revision = body(mvc.perform(as(tenant, post("/api/v1/rate-cards/" + clientId + "/revise")).content("""
                        {"validFrom":"%s"}
                        """.formatted(today.plusDays(1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.card.version").value(2))
                .andExpect(jsonPath("$.card.status").value("DRAFT"))
                .andExpect(jsonPath("$.previousCardId").value(clientId))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.charges.length()").value(1))
                .andReturn());
        String revisionId = JsonPath.read(revision, "$.card.id");
        activate(tenant, revisionId).andExpect(status().isOk());
        mvc.perform(as(tenant, get("/api/v1/rate-cards/" + clientId)))
                .andExpect(jsonPath("$.card.validTo").value(today.toString()));

        // Only one active standard card for the same dates
        String second = body(mvc.perform(as(tenant, post("/api/v1/rate-cards")).content("""
                        {"name":"Second standard","validFrom":"%s","lines":[{"rateBasis":"PER_TRIP","rate":1000}]}
                        """.formatted(today.minusDays(5)))).andExpect(status().isCreated()).andReturn());
        activate(tenant, JsonPath.read(second, "$.card.id")).andExpect(status().isConflict());

        // Other tenants see none of it
        mvc.perform(as(other, get("/api/v1/rate-cards"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(other, get("/api/v1/rate-cards/" + standardId))).andExpect(status().isNotFound());
        lookup(other, null, ludhiana, mumbai, "80", null).andExpect(jsonPath("$.source").value("NONE"));
    }

    private org.springframework.test.web.servlet.ResultActions activate(UUID tenant, String cardId) throws Exception {
        return mvc.perform(as(tenant, put("/api/v1/rate-cards/" + cardId + "/status")).content("{\"status\":\"ACTIVE\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions lookup(UUID tenant, String partyId, String origin,
                                                                      String destination, String weight,
                                                                      String extra) throws Exception {
        MockHttpServletRequestBuilder request = as(tenant, get("/api/v1/rate-cards/lookup"))
                .param("originCityId", origin)
                .param("destinationCityId", destination)
                .param("actualWeightKg", weight);
        if (partyId != null) {
            request.param("partyId", partyId);
        }
        if (extra != null) {
            String[] kv = extra.split("=");
            request.param(kv[0], kv[1]);
        }
        return mvc.perform(request).andExpect(status().isOk());
    }

    private String cityId(UUID tenant, String name) throws Exception {
        String json = body(mvc.perform(as(tenant, get("/api/v1/cities")).param("q", name))
                .andExpect(status().isOk()).andReturn());
        return first(json, "$[?(@.name=='" + name + "')].id");
    }

    private static String first(String json, String path) {
        List<String> values = JsonPath.read(json, path);
        return values.getFirst();
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

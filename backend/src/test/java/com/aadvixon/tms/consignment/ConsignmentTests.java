package com.aadvixon.tms.consignment;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Booking, pricing from rate cards, numbering, overrides, edits and cancellation. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"tms.tenancy.header-enabled=true", "tms.platform.admin-api-enabled=true"})
class ConsignmentTests {

    @Autowired
    WebApplicationContext context;

    @Autowired
    TenantFilter tenantFilter;

    MockMvc mvc;
    UUID tenant;
    String location;
    String ludhiana;
    String mumbai;
    String consignor;
    String consignee;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(tenantFilter).build();
        tenant = createTenant("cn-" + shortId());
        String company = body(mvc.perform(as(tenant, post("/api/v1/companies")).content("""
                        {"legalName":"Acme Roadways","countryCode":"IN","stateCode":"PB",
                         "businessTypes":["TRANSPORTER"],"ownershipType":"PARTNERSHIP"}
                        """)).andExpect(status().isCreated()).andReturn());
        location = JsonPath.read(body(mvc.perform(as(tenant, post("/api/v1/locations")).content("""
                        {"companyId":"%s","code":"LDH","name":"Ludhiana HO","types":["HEAD_OFFICE","BOOKING_OFFICE"]}
                        """.formatted(JsonPath.<String>read(company, "$.id"))))
                .andExpect(status().isCreated()).andReturn()), "$.id");
        ludhiana = cityId("Ludhiana");
        mumbai = cityId("Mumbai");
        consignor = partyId("""
                {"legalName":"Sharma Traders","roles":["CONSIGNOR"],"mobile":"9876543210",
                 "taxRegistrations":[{"taxType":"GSTIN","number":"03AAACS1234B1Z5"}]}""");
        consignee = partyId("""
                {"legalName":"Mehta Hardware","roles":["CONSIGNEE"]}""");

        // Standard rates: 5 per kg, minimum 300, for any lane
        String card = body(mvc.perform(as(tenant, post("/api/v1/rate-cards")).content("""
                        {"name":"Standard","validFrom":"%s","lines":[{"rateBasis":"PER_KG","rate":5,"minCharge":300,"transitDays":4}]}
                        """.formatted(LocalDate.now().minusDays(10)))).andExpect(status().isCreated()).andReturn());
        mvc.perform(as(tenant, put("/api/v1/rate-cards/" + JsonPath.read(card, "$.card.id") + "/status"))
                .content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());
    }

    @Test
    void bookingIsPricedNumberedEditedAndCancelled() throws Exception {
        // Preview: 100 kg x 5 = 500 freight + GR charge 20; tax under reverse charge
        mvc.perform(as(tenant, post("/api/v1/consignments/preview")).content(booking("PAID", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateSource").value("STANDARD"))
                .andExpect(jsonPath("$.totalPackages").value(5))
                .andExpect(jsonPath("$.chargeableWeightKg").value(100.0))
                .andExpect(jsonPath("$.freight").value(500.0))
                .andExpect(jsonPath("$.charges[0].code").value("GR_CHARGE"))
                .andExpect(jsonPath("$.taxPaidBy").value("RCM"))
                .andExpect(jsonPath("$.total").value(520.0))
                .andExpect(jsonPath("$.needsApproval").value(false))
                .andExpect(jsonPath("$.transitDays").value(4));

        // Book: number from the office series, created on first use; parties snapshotted
        String first = body(book(booking("PAID", ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.cnNo").value(startsWith("LDH/")))
                .andExpect(jsonPath("$.summary.cnNo").value(endsWith("/00001")))
                .andExpect(jsonPath("$.summary.status").value("BOOKED"))
                .andExpect(jsonPath("$.summary.billToName").value("Sharma Traders"))
                .andExpect(jsonPath("$.consignorTaxId").value("03AAACS1234B1Z5"))
                .andExpect(jsonPath("$.packages.length()").value(2))
                .andExpect(jsonPath("$.charges[0].amount").value(20.0))
                .andExpect(jsonPath("$.summary.total").value(520.0))
                .andExpect(jsonPath("$.events[0].status").value("BOOKED"))
                .andReturn());
        String firstId = JsonPath.read(first, "$.summary.id");

        // To-pay bills the consignee; forward-charge tax 12% on 520
        book(booking("TO_PAY", ",\"taxPaidBy\":\"TRANSPORTER\",\"taxRate\":12"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.cnNo").value(endsWith("/00002")))
                .andExpect(jsonPath("$.summary.billToName").value("Mehta Hardware"))
                .andExpect(jsonPath("$.taxAmount").value(62.4))
                .andExpect(jsonPath("$.summary.total").value(582.4));

        // Freight below the rate and discounts need approval with a reason
        mvc.perform(as(tenant, post("/api/v1/consignments/preview")).content(booking("PAID", ",\"freight\":400")))
                .andExpect(jsonPath("$.needsApproval").value(true))
                .andExpect(jsonPath("$.approvalReasons[0]").value(startsWith("Freight 400.00 is below")));
        book(booking("PAID", ",\"freight\":400")).andExpect(status().isConflict());
        book(booking("PAID", ",\"freight\":400,\"overrideReason\":\"Regular client\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.freight").value(400.0))
                .andExpect(jsonPath("$.quotedFreight").value(500.0))
                .andExpect(jsonPath("$.approvedBy").value("tester"));
        book(booking("PAID", ",\"discount\":1000,\"overrideReason\":\"x\"")).andExpect(status().isConflict());

        // Locked GR charge cannot be changed without approval reason
        String grCharge = JsonPath.<List<String>>read(body(mvc.perform(as(tenant, get("/api/v1/charge-heads"))).andReturn()),
                "$[?(@.code=='GR_CHARGE')].id").getFirst();
        book(booking("PAID", ",\"charges\":[{\"chargeHeadId\":\"%s\",\"amount\":0}]".formatted(grCharge)))
                .andExpect(status().isConflict());

        // Pre-printed book number; duplicates refused
        book(booking("PAID", ",\"manualNo\":\"b-1001\",\"manualBookNo\":\"BK-11\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.cnNo").value("B-1001"))
                .andExpect(jsonPath("$.creation").value("MANUAL"));
        book(booking("PAID", ",\"manualNo\":\"B-1001\"")).andExpect(status().isConflict());

        // The same e-way bill cannot be used twice
        book(booking("PAID", ",\"ewayBillNo\":\"123456789012\"")).andExpect(status().isCreated());
        book(booking("PAID", ",\"ewayBillNo\":\"123456789012\"")).andExpect(status().isConflict());

        // Bill-to on credit hold cannot be booked as TBB
        String held = partyId("""
                {"legalName":"Late Payers Ltd","roles":["BILL_TO"],"status":"ON_HOLD"}""");
        book(booking("TBB", ",\"billToId\":\"%s\"".formatted(held))).andExpect(status().isConflict());

        // Edit before loading: more weight -> re-priced; the number stays
        mvc.perform(as(tenant, put("/api/v1/consignments/" + firstId)).content(booking("PAID", "")
                        .replace("\"actualWeightKg\":60", "\"actualWeightKg\":160")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.cnNo").value(endsWith("/00001")))
                .andExpect(jsonPath("$.freight").value(1000.0))
                .andExpect(jsonPath("$.events[1].status").value("EDITED"));

        // Cancel keeps the record; no more edits or cancels
        mvc.perform(as(tenant, post("/api/v1/consignments/" + firstId + "/cancel")).content("{\"reason\":\"Wrong consignee\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Wrong consignee"));
        mvc.perform(as(tenant, put("/api/v1/consignments/" + firstId)).content(booking("PAID", "")))
                .andExpect(status().isConflict());
        mvc.perform(as(tenant, post("/api/v1/consignments/" + firstId + "/cancel")).content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict());

        // Search and tenant isolation
        mvc.perform(as(tenant, get("/api/v1/consignments/offices")))
                .andExpect(jsonPath("$[0].code").value("LDH"))
                .andExpect(jsonPath("$[0].consignmentNoteName").value("GR / Bilty"));
        mvc.perform(as(tenant, get("/api/v1/consignments")).param("q", "mehta"))
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
        mvc.perform(as(tenant, get("/api/v1/consignments")).param("status", "CANCELLED"))
                .andExpect(jsonPath("$.length()").value(1));
        UUID other = createTenant("cn-other-" + shortId());
        mvc.perform(as(other, get("/api/v1/consignments"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(other, get("/api/v1/consignments/" + firstId))).andExpect(status().isNotFound());
    }

    private String booking(String paymentType, String extra) {
        return """
                {"bookingLocationId":"%s","originCityId":"%s","destinationCityId":"%s","paymentType":"%s",
                 "consignorId":"%s","consigneeId":"%s","invoiceNumbers":["INV-77"],
                 "packages":[{"packages":3,"packageType":"carton","saidToContain":"Hardware","actualWeightKg":60,"value":20000},
                             {"packages":2,"packageType":"bag","saidToContain":"Fittings","actualWeightKg":40,"value":10000}]%s}
                """.formatted(location, ludhiana, mumbai, paymentType, consignor, consignee, extra);
    }

    private ResultActions book(String json) throws Exception {
        return mvc.perform(as(tenant, post("/api/v1/consignments")).content(json));
    }

    private String partyId(String json) throws Exception {
        return JsonPath.read(body(mvc.perform(as(tenant, post("/api/v1/parties")).content(json))
                .andExpect(status().isCreated()).andReturn()), "$.id");
    }

    private String cityId(String name) throws Exception {
        String json = body(mvc.perform(as(tenant, get("/api/v1/cities")).param("q", name)).andReturn());
        return JsonPath.<List<String>>read(json, "$[?(@.name=='" + name + "')].id").getFirst();
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

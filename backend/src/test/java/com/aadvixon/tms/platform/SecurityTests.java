package com.aadvixon.tms.platform;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aadvixon.tms.TestcontainersConfiguration;
import com.aadvixon.tms.platform.tenancy.TenantFilter;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Signed-in users (JWT): tenant from Keycloak groups, user linked by email on first
 * login, and module permissions enforced from roles.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"tms.tenancy.header-enabled=true", "tms.platform.admin-api-enabled=true"})
class SecurityTests {

    @Autowired
    WebApplicationContext context;

    @Autowired
    TenantFilter tenantFilter;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(tenantFilter).build();
    }

    @Test
    void permissionsComeFromTheUsersRoles() throws Exception {
        String code = "sec-" + UUID.randomUUID().toString().substring(0, 8);
        String ownerEmail = "owner@" + code + ".test";
        String clerkEmail = "clerk@" + code + ".test";

        String tenant = mvc.perform(post("/api/v1/platform/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Sec Test","ownerEmail":"%s","ownerName":"Owner"}
                                """.formatted(code, ownerEmail)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tenantId = JsonPath.read(tenant, "$.id");

        // First login links the invited owner by email; the owner has every permission
        mvc.perform(get("/api/v1/me").with(login("sub-owner-" + code, ownerEmail, "/tenants/" + code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value(code))
                .andExpect(jsonPath("$.email").value(ownerEmail))
                .andExpect(jsonPath("$.permissions.length()").value(176))
                .andExpect(jsonPath("$.developmentMode").value(false));

        // Owner creates a booking clerk
        String roles = mvc.perform(get("/api/v1/roles").with(login("sub-owner-" + code, ownerEmail, "/tenants/" + code)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String clerkRoleId = ((List<String>) JsonPath.read(roles, "$[?(@.code=='BOOKING_CLERK')].id")).getFirst();
        mvc.perform(post("/api/v1/users").with(login("sub-owner-" + code, ownerEmail, "/tenants/" + code))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userType":"STAFF","fullName":"Clerk","email":"%s","status":"INVITED","roleIds":["%s"]}
                                """.formatted(clerkEmail, clerkRoleId)))
                .andExpect(status().isCreated());

        JwtRequestPostProcessor clerk = login("sub-clerk-" + code, clerkEmail, "/tenants/" + code);

        // Clerk may create and view parties ...
        String party = mvc.perform(post("/api/v1/parties").with(clerk).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"Walk-in Sender","roles":["CONSIGNOR"],"mobile":"9000000001"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String partyId = JsonPath.read(party, "$.id");
        mvc.perform(get("/api/v1/parties").with(clerk)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/country-packs").with(clerk)).andExpect(status().isOk());

        // ... but not delete them, manage users, or read the audit log
        mvc.perform(delete("/api/v1/parties/" + partyId).param("reason", "x").with(clerk))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You do not have permission: party.delete"));
        mvc.perform(get("/api/v1/users").with(clerk)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit").with(clerk)).andExpect(status().isForbidden());

        // Audit shows the clerk's email as the user who created the party
        mvc.perform(get("/api/v1/audit").param("table", "party").param("recordId", partyId)
                        .with(login("sub-owner-" + code, ownerEmail, "/tenants/" + code)))
                .andExpect(jsonPath("$[0].userId").value(clerkEmail));

        // A token cannot pick another tenant with the dev header
        mvc.perform(get("/api/v1/parties").with(clerk)
                        .header(TenantFilter.TENANT_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void loginsWithoutAccessAreRejected() throws Exception {
        String code = "rej-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/platform/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Reject Test\"}"))
                .andExpect(status().isCreated());

        // No tenant group in the token
        mvc.perform(get("/api/v1/parties").with(login("sub-a", "a@x.test")))
                .andExpect(status().isForbidden());
        // Tenant exists but there is no user for this email
        mvc.perform(get("/api/v1/parties").with(login("sub-b", "nobody@x.test", "/tenants/" + code)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("No user is set up")));
        // Unknown tenant
        mvc.perform(get("/api/v1/parties").with(login("sub-c", "c@x.test", "/tenants/does-not-exist")))
                .andExpect(status().isForbidden());
        // Several tenants: must choose one, and only from its own groups
        mvc.perform(get("/api/v1/parties").with(login("sub-d", "d@x.test", "/tenants/" + code, "/tenants/other")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("X-Tenant-Code")));
        mvc.perform(get("/api/v1/parties").with(login("sub-d", "d@x.test", "/tenants/" + code))
                        .header(TenantFilter.TENANT_CODE_HEADER, "someone-else"))
                .andExpect(status().isForbidden());
    }

    private static JwtRequestPostProcessor login(String subject, String email, String... groups) {
        return jwt().jwt(token -> token.subject(subject).claim("email", email).claim("groups", List.of(groups)));
    }
}

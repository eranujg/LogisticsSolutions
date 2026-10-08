package com.aadvixon.tms.platform;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aadvixon.tms.TestcontainersConfiguration;
import com.aadvixon.tms.platform.tenancy.TenantFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** With the development switches off, nothing works without a valid login. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"tms.tenancy.header-enabled=false", "tms.platform.admin-api-enabled=false"})
class ProductionModeSecurityTests {

    @Autowired
    WebApplicationContext context;

    @Autowired
    TenantFilter tenantFilter;

    @Test
    void headersAndPlatformApiAreNotAccepted() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(tenantFilter).build();

        mvc.perform(get("/api/v1/parties").header(TenantFilter.TENANT_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/platform/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"x\",\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/parties").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/system/info")).andExpect(status().isOk());
    }
}

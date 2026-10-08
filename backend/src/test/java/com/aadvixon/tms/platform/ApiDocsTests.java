package com.aadvixon.tms.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aadvixon.tms.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ApiDocsTests {

    @Autowired
    WebApplicationContext context;

    @Test
    void openApiDescribesTheApiAndTenantHeader() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/parties']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.tenant.name").value("X-Tenant-Id"));
    }
}

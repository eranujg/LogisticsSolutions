package com.aadvixon.tms.platform.apidocs;

import com.aadvixon.tms.platform.tenancy.TenantFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description for Swagger UI at /swagger-ui.
 *
 * <p>The tenant and user headers are declared as API keys, so the
 * <em>Authorize</em> button lets you enter them once for every request.
 */
@Configuration(proxyBeanMethods = false)
class ApiDocsConfiguration {

    private static final String TENANT = "tenant";
    private static final String USER = "user";

    @Bean
    OpenAPI tmsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Transport Platform API")
                        .version("v1")
                        .description("""
                                Development API explorer. Click **Authorize** and enter a tenant id \
                                (from POST /api/v1/platform/tenants) and any user name. \
                                Headers are accepted only when tms.tenancy.header-enabled=true."""))
                .components(new Components()
                        .addSecuritySchemes(TENANT, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(TenantFilter.TENANT_HEADER)
                                .description("Tenant UUID"))
                        .addSecuritySchemes(USER, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(TenantFilter.USER_HEADER)
                                .description("User name recorded in the audit log")))
                .addSecurityItem(new SecurityRequirement().addList(TENANT).addList(USER));
    }
}

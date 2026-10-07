package com.aadvixon.tms.platform.tenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform (vendor) administration: creates tenants for new customers.
 *
 * <p>Enabled only when {@code tms.platform.admin-api-enabled=true}; it must stay off
 * in customer environments until it is protected by platform-admin login.
 */
@RestController
@RequestMapping("/api/v1/platform/tenants")
@ConditionalOnProperty(name = "tms.platform.admin-api-enabled", havingValue = "true")
class TenantAdminController {

    record CreateTenantRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[a-z0-9][a-z0-9-]*", message = "lowercase letters, digits and hyphens") String code,
            @NotBlank @Size(max = 200) String name) {
    }

    record TenantResponse(UUID id, String code, String name, String status, OffsetDateTime createdAt) {
    }

    private final TenantProvisioningService provisioning;

    TenantAdminController(TenantProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TenantResponse create(@Valid @RequestBody CreateTenantRequest request) {
        return provisioning.createTenant(request.code(), request.name());
    }

    @GetMapping
    List<TenantResponse> list() {
        return provisioning.listTenants();
    }
}

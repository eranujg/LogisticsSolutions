package com.aadvixon.tms.platform.security;

import com.aadvixon.tms.platform.tenancy.TenantContext;
import com.aadvixon.tms.platform.tenancy.TenantFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user, tenant and permissions; the frontend uses this to build menus. */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    record Me(UUID tenantId, String tenantCode, UUID userId, String fullName, String email,
              List<String> permissions, List<UUID> locationIds, boolean developmentMode) {
    }

    @GetMapping
    Me me(HttpServletRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        if (request.getAttribute(TenantFilter.RESOLVED_USER_ATTRIBUTE) instanceof CurrentUserResolver.ResolvedUser user) {
            return new Me(user.tenantId(), user.tenantCode(), user.userId(), user.fullName(), user.email(),
                    user.permissions().stream().sorted().toList(), user.locationIds(), false);
        }
        // Development header mode: unrestricted
        return new Me(tenantId, null, null, TenantContext.currentUserId(), null, List.of("*"), List.of(), true);
    }
}

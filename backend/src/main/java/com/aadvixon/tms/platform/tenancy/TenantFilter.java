package com.aadvixon.tms.platform.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets the {@link TenantContext} for each API request.
 *
 * <p>Until Keycloak login is added, the tenant and user come from the
 * {@code X-Tenant-Id} and {@code X-User-Id} headers. This is enabled only when
 * {@code tms.tenancy.header-enabled=true} (local development and tests). In any
 * other environment the tenant must come from a verified login token.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String USER_HEADER = "X-User-Id";

    private final boolean headerEnabled;

    public TenantFilter(@Value("${tms.tenancy.header-enabled:false}") boolean headerEnabled) {
        this.headerEnabled = headerEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID tenantId = null;
        String userId = null;
        if (headerEnabled) {
            String tenantHeader = request.getHeader(TENANT_HEADER);
            if (tenantHeader != null && !tenantHeader.isBlank()) {
                try {
                    tenantId = UUID.fromString(tenantHeader.trim());
                } catch (IllegalArgumentException e) {
                    response.setStatus(HttpStatus.BAD_REQUEST.value());
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    response.getWriter().write(
                            "{\"title\":\"Bad Request\",\"status\":400,\"detail\":\"X-Tenant-Id must be a UUID\"}");
                    return;
                }
            }
            String userHeader = request.getHeader(USER_HEADER);
            if (userHeader != null && !userHeader.isBlank()) {
                userId = userHeader.trim();
            }
        }

        TenantContext.set(new TenantContext.Scope(tenantId, userId, false));
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}

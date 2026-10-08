package com.aadvixon.tms.platform.tenancy;

import com.aadvixon.tms.platform.security.CurrentUserResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets the {@link TenantContext} for each request. Runs after the Spring Security
 * filter chain (order -100), so a verified JWT is already available.
 *
 * <ol>
 *   <li>Signed-in user (JWT): tenant from Keycloak groups, user and permissions from
 *       the database (see {@link CurrentUserResolver}).</li>
 *   <li>Development only ({@code tms.tenancy.header-enabled=true}): tenant and user
 *       from {@code X-Tenant-Id} / {@code X-User-Id}, with unrestricted permissions.</li>
 * </ol>
 */
@Component
@Order(0)
public class TenantFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String USER_HEADER = "X-User-Id";
    public static final String TENANT_CODE_HEADER = "X-Tenant-Code";
    public static final String RESOLVED_USER_ATTRIBUTE = "tms.resolvedUser";

    private final boolean headerEnabled;
    private final CurrentUserResolver resolver;

    public TenantFilter(@Value("${tms.tenancy.header-enabled:false}") boolean headerEnabled,
                        CurrentUserResolver resolver) {
        this.headerEnabled = headerEnabled;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        TenantContext.Scope scope;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken token && requiresTenant(request)) {
            Jwt jwt = token.getToken();
            try {
                CurrentUserResolver.ResolvedUser user = resolver.resolve(jwt.getSubject(),
                        jwt.getClaimAsString("email"), jwt.getClaimAsStringList("groups"),
                        request.getHeader(TENANT_CODE_HEADER));
                request.setAttribute(RESOLVED_USER_ATTRIBUTE, user);
                scope = new TenantContext.Scope(user.tenantId(), user.email(), false, user.permissions());
            } catch (CurrentUserResolver.AccessProblem e) {
                writeProblem(response, HttpStatus.FORBIDDEN, e.getMessage());
                return;
            }
        } else if (headerEnabled) {
            UUID tenantId = null;
            String tenantHeader = request.getHeader(TENANT_HEADER);
            if (tenantHeader != null && !tenantHeader.isBlank()) {
                try {
                    tenantId = UUID.fromString(tenantHeader.trim());
                } catch (IllegalArgumentException e) {
                    writeProblem(response, HttpStatus.BAD_REQUEST, "X-Tenant-Id must be a UUID");
                    return;
                }
            }
            String userHeader = request.getHeader(USER_HEADER);
            String userId = userHeader == null || userHeader.isBlank() ? null : userHeader.trim();
            scope = new TenantContext.Scope(tenantId, userId, false, null);
        } else {
            scope = new TenantContext.Scope(null, null, false, java.util.Set.of());
        }

        TenantContext.set(scope);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /** Public endpoints (health, API docs, platform admin) do not resolve a tenant user. */
    private static boolean requiresTenant(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/") && !path.startsWith("/api/v1/system/") && !path.startsWith("/api/v1/platform/");
    }

    private static void writeProblem(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String safe = detail.replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + safe + "\"}");
    }
}

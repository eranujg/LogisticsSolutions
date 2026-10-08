package com.aadvixon.tms.platform.security;

import com.aadvixon.tms.platform.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Checks module permissions for every API call, in one place.
 *
 * <p>The first path segment after {@code /api/v1/} maps to a permission module and
 * the HTTP method to an action: GET = view, POST = create, PUT/PATCH = edit,
 * DELETE = delete (restoring from the recycle bin counts as edit).
 * <b>Paths not listed are denied</b>, so a new endpoint must be added here.
 */
@Configuration(proxyBeanMethods = false)
class PermissionInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final Map<String, String> MODULES = Map.ofEntries(
            Map.entry("companies", "company"),
            Map.entry("locations", "location"),
            Map.entry("cities", "city"),
            Map.entry("city-services", "city"),
            Map.entry("users", "user"),
            Map.entry("roles", "role"),
            Map.entry("permissions", "role"),
            Map.entry("parties", "party"),
            Map.entry("number-series", "number_series"),
            Map.entry("audit", "audit"),
            Map.entry("recycle-bin", "recycle_bin"));

    /** Open to any user of the tenant (reference data and own profile). */
    private static final Set<String> ANY_USER = Set.of("country-packs", "me");

    /** Not tenant APIs: protected elsewhere (public health, dev-only platform admin). */
    private static final Set<String> NOT_CHECKED = Set.of("system", "platform");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI().substring("/api/v1/".length());
        String[] segments = path.split("/");
        String resource = segments[0];
        if (NOT_CHECKED.contains(resource)) {
            return true;
        }
        TenantContext.Scope scope = TenantContext.current().orElse(null);
        if (ANY_USER.contains(resource)) {
            return true;
        }
        String module = MODULES.get(resource);
        if (module == null) {
            throw new PermissionDeniedException("unknown resource " + resource);
        }
        String permission = module + "." + action(request.getMethod(), segments);
        if (scope == null || !scope.hasPermission(permission)) {
            throw new PermissionDeniedException(permission);
        }
        return true;
    }

    private static String action(String method, String[] segments) {
        boolean restore = segments.length > 0 && "restore".equals(segments[segments.length - 1]);
        return switch (method) {
            case "GET", "HEAD", "OPTIONS" -> "view";
            case "POST" -> restore ? "edit" : "create";
            case "PUT", "PATCH" -> "edit";
            case "DELETE" -> "delete";
            default -> "unknown";
        };
    }
}

package com.aadvixon.tms.platform.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Holds the tenant and user for the current thread.
 *
 * <p>Every database connection handed to the application is prepared from this
 * context (see {@link TenantAwareDataSource}): tenant requests run as the
 * {@code tms_app} role with {@code app.tenant_id} set, so PostgreSQL row-level
 * security limits them to their own rows. System scope (tenant provisioning)
 * runs as the pool user and must be used sparingly.
 */
public final class TenantContext {

    /** Tenant and user for the current unit of work. */
    public record Scope(UUID tenantId, String userId, boolean system) {
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<Scope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The current tenant, or an exception if the request has no tenant. */
    public static UUID requireTenantId() {
        Scope scope = CURRENT.get();
        if (scope == null || scope.tenantId() == null) {
            throw new TenantRequiredException();
        }
        return scope.tenantId();
    }

    /** The current user id for audit columns, or {@code "system"}. */
    public static String currentUserId() {
        Scope scope = CURRENT.get();
        return scope == null || scope.userId() == null ? "system" : scope.userId();
    }

    public static <T> T runAsTenant(UUID tenantId, String userId, Supplier<T> work) {
        return runIn(new Scope(tenantId, userId, false), work);
    }

    /**
     * Runs work as the pool user (RLS bypassed for superusers). Only for platform
     * operations such as creating a tenant. {@code tenantId} may be null.
     */
    public static <T> T runAsSystem(UUID tenantId, Supplier<T> work) {
        return runIn(new Scope(tenantId, "system", true), work);
    }

    static <T> T runIn(Scope scope, Supplier<T> work) {
        Scope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    static void set(Scope scope) {
        CURRENT.set(scope);
    }

    static void clear() {
        CURRENT.remove();
    }
}

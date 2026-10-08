package com.aadvixon.tms.platform.tenancy;

/** Thrown when tenant-scoped work is attempted without a tenant. */
public class TenantRequiredException extends RuntimeException {

    public TenantRequiredException() {
        super("A tenant is required for this request (X-Tenant-Id header in development).");
    }
}

package com.moveinsync.distance.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Temporary: every request belongs to one configured tenant. Replaced by a token-based
 * implementation when login is added, at which point the tenant comes from the verified token.
 */
@Component
public class FixedTenantContext implements TenantContext {

    private final String tenantId;

    public FixedTenantContext(@Value("${app.default-tenant:demo}") String tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public String currentTenantId() {
        return tenantId;
    }
}

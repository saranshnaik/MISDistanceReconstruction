package com.moveinsync.distance.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Reads who is calling from the VERIFIED token. The tenant is never taken from the URL, the
 * body or a header, so a caller cannot ask for another tenant's data by naming it.
 */
@Component
public class JwtTenantContext implements TenantContext {

    @Override
    public String currentTenantId() {
        return claim(JwtService.TENANT_CLAIM);
    }

    @Override
    public Role currentRole() {
        return Role.valueOf(claim(JwtService.ROLE_CLAIM));
    }

    @Override
    public String currentEmployeeRef() {
        Jwt jwt = jwt();
        return jwt.getClaimAsString(JwtService.EMPLOYEE_CLAIM);
    }

    private String claim(String name) {
        String value = jwt().getClaimAsString(name);
        if (value == null) {
            throw new IllegalStateException("token has no " + name + " claim");
        }
        return value;
    }

    private Jwt jwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("no authenticated token in this request");
        }
        return jwt;
    }
}

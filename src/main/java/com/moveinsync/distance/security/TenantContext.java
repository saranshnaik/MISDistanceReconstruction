package com.moveinsync.distance.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Who is calling, read from the VERIFIED token. The tenant never comes from the URL, body or
 * a header, so nobody can get another tenant's data by naming it.
 */
@Component
public class TenantContext {

    public String currentTenantId() {
        return claim(JwtService.TENANT_CLAIM);
    }

    public Role currentRole() {
        return Role.valueOf(claim(JwtService.ROLE_CLAIM));
    }

    /** For EMPLOYEE accounts: which employee this login is. Null for the other roles. */
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

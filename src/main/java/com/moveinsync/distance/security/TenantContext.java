package com.moveinsync.distance.security;

/**
 * Who is calling, as far as data access goes. Controllers never take the tenant from the
 * request body or a header the caller controls; they ask this.
 */
public interface TenantContext {

    String currentTenantId();

    Role currentRole();

    /** For EMPLOYEE accounts: which employee this login is. Null for other roles. */
    String currentEmployeeRef();
}

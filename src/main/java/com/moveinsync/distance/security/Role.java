package com.moveinsync.distance.security;

/**
 * EMPLOYEE: a rider, sees only their own trips.
 * OPERATOR: ops staff of one tenant, submits trips and handles day-to-day.
 * ADMIN:    can also close invoices and see money adjustments.
 */
public enum Role {
    EMPLOYEE,
    OPERATOR,
    ADMIN
}

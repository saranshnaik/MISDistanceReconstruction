package com.moveinsync.distance.security;

/**
 * EMPLOYEE: a rider, sees only their own trips.
 * OPERATOR: ops staff of one tenant, submits trips and does the day-to-day.
 * ADMIN: also closes invoices and sees adjustments.
 */
public enum Role {
    EMPLOYEE,
    OPERATOR,
    ADMIN
}

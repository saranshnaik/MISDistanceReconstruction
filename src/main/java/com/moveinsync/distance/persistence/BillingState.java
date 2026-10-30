package com.moveinsync.distance.persistence;

/** UNBILLED trips can still change freely. Once BILLED, only signed adjustments are allowed. */
public enum BillingState {
    UNBILLED,
    BILLED
}

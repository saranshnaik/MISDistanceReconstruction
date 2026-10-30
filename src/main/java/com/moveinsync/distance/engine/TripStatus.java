package com.moveinsync.distance.engine;

/**
 * OK: every real gap was priced by the rules. DEGRADED: the routing provider was unavailable,
 * so at least one gap is only an estimate (straight line x 1.4). A degraded trip must be
 * recomputed when the provider is back, and must not be billed before that.
 */
public enum TripStatus {
    OK,
    DEGRADED
}

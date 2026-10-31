package com.moveinsync.distance.engine;

/**
 * OK: every real gap was priced by the rules. DEGRADED: provider unavailable, so at least
 * one gap is only an estimate (straight line x 1.4). Recompute once the provider is back, and
 * don't bill before that.
 */
public enum TripStatus {
    OK,
    DEGRADED
}

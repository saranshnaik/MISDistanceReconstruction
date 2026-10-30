package com.moveinsync.distance.engine.model;

/** The rule that produced a segment's distance. Shown on every segment in the output. */
public enum SegmentRule {
    /** Two pings less than 70 s apart at a believable speed: straight line. */
    SHORT_GAP_AERIAL,
    /** A long gap but the cab barely moved (under 5 km/h): straight line. */
    PARKED,
    /** A real gap: the driving distance from the routing provider was accepted. */
    ROUTED,
    /** The routing provider's answer was longer than the 100 km sanity limit; billed as 0. */
    REJECTED_DISTANCE,
    /** The distance would need an impossible speed even with a generous detour; billed as 0. */
    REJECTED_SPEED,
    /** The provider was down, so straight line x 1.4 was used as an estimate. */
    DEGRADED_ESTIMATE
}

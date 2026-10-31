package com.moveinsync.distance.engine.model;

/** Which rule priced a segment. Shown on every segment in the output. */
public enum SegmentRule {
    /** Under 70 s apart at a believable speed: straight line. */
    SHORT_GAP_AERIAL,
    /** Long gap but the cab barely moved (under 5 km/h): straight line. */
    PARKED,
    /** Real gap, provider's driving distance accepted. */
    ROUTED,
    /** Provider said more than 100 km; billed as 0. */
    REJECTED_DISTANCE,
    /** Would need an impossible speed even with a generous detour; billed as 0. */
    REJECTED_SPEED,
    /** Provider was down, so straight line x 1.4 as an estimate. */
    DEGRADED_ESTIMATE
}

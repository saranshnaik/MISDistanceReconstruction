package com.moveinsync.distance.engine.model;

/**
 * The stretch between two neighbouring points of the track, and how much of it we bill.
 * Distances are whole millimetres so that adding segments up never drifts.
 *
 * @param durationMs time between the two points
 * @param aerialMm   straight-line distance
 * @param routedMm   driving distance from the routing provider, or null if none was asked for
 * @param billedMm   what actually counts towards the bill (0 for rejected segments)
 * @param rule       the rule that decided billedMm
 */
public record Segment(
        TrackPoint from,
        TrackPoint to,
        long durationMs,
        long aerialMm,
        Long routedMm,
        long billedMm,
        SegmentRule rule) {
}

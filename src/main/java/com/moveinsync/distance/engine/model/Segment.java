package com.moveinsync.distance.engine.model;

/**
 * The stretch between two neighbouring track points and what we bill for it. Whole
 * millimetres so sums never drift. routedMm is null when no route was asked for, billedMm is 0
 * for rejected segments, and rule says which rule decided.
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

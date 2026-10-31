package com.moveinsync.distance.engine.cleaning;

/** What was thrown away and why. Each received ping lands in exactly one bucket. */
public record CleaningReport(
        int received,
        int outsideTripWindow,
        int invalidCoordinates,
        int lowAccuracy,
        int duplicates,
        int teleports,
        int kept) {
}

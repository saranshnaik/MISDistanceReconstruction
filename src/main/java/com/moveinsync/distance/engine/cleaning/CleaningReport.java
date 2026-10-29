package com.moveinsync.distance.engine.cleaning;

/**
 * How many pings were thrown away and why. Every received ping ends up in exactly one bucket,
 * so {@code received == dropped counts + kept}.
 */
public record CleaningReport(
        int received,
        int outsideTripWindow,
        int invalidCoordinates,
        int lowAccuracy,
        int duplicates,
        int teleports,
        int kept) {
}

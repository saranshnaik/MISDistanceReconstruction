package com.moveinsync.distance.engine.cleaning;

import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.RuleConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the raw device stream into a clean, time-ordered list of pings.
 *
 * Steps, always in this order:
 *  1. drop pings outside the trip window (we only bill the trip itself)
 *  2. drop pings with impossible coordinates
 *  3. drop low-accuracy pings
 *  4. sort by time and remove duplicates (same timestamp)
 */
public class PingCleaner {

    /**
     * Sort order that also decides which duplicate wins: for the same timestamp the most
     * accurate ping comes first, and lat/lon break exact ties. Because the winner depends only
     * on the data (never on arrival order), shuffled input gives identical output.
     */
    private static final Comparator<Ping> TIME_THEN_BEST_FIRST =
            Comparator.comparing(Ping::time)
                    .thenComparingDouble(Ping::accuracyM)
                    .thenComparingDouble(p -> p.point().lat())
                    .thenComparingDouble(p -> p.point().lon());

    private final RuleConfig config;

    public PingCleaner(RuleConfig config) {
        this.config = config;
    }

    public CleanedPings clean(List<Ping> raw, Instant tripStart, Instant tripEnd) {
        int outsideWindow = 0;
        int invalidCoordinates = 0;
        int lowAccuracy = 0;

        List<Ping> usable = new ArrayList<>();
        for (Ping ping : raw) {
            if (ping.time().isBefore(tripStart) || ping.time().isAfter(tripEnd)) {
                outsideWindow++;
            } else if (!ping.point().isValid()) {
                invalidCoordinates++;
            } else if (!isAccurateEnough(ping)) {
                lowAccuracy++;
            } else {
                usable.add(ping);
            }
        }

        usable.sort(TIME_THEN_BEST_FIRST);
        List<Ping> unique = new ArrayList<>(usable.size());
        for (Ping ping : usable) {
            boolean sameTimeAsPrevious = !unique.isEmpty()
                    && unique.get(unique.size() - 1).time().equals(ping.time());
            if (!sameTimeAsPrevious) {
                unique.add(ping);
            }
        }
        int duplicates = usable.size() - unique.size();

        CleaningReport report = new CleaningReport(
                raw.size(), outsideWindow, invalidCoordinates, lowAccuracy, duplicates, unique.size());
        return new CleanedPings(unique, report);
    }

    /** A missing, negative or NaN accuracy is treated as not trustworthy. */
    private boolean isAccurateEnough(Ping ping) {
        double accuracy = ping.accuracyM();
        return accuracy >= 0 && accuracy <= config.maxAccuracyM();
    }
}

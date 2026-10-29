package com.moveinsync.distance.engine.cleaning;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.RuleConfig;
import java.time.Duration;
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
 *  5. drop teleports: pings that would need an impossible speed to reach
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

        List<Ping> plausible = dropTeleports(unique);
        int teleports = unique.size() - plausible.size();

        CleaningReport report = new CleaningReport(
                raw.size(), outsideWindow, invalidCoordinates, lowAccuracy, duplicates, teleports,
                plausible.size());
        return new CleanedPings(plausible, report);
    }

    /**
     * Walks the pings in time order and compares each one with the last ping we accepted.
     * If reaching it would need a speed above the limit, it is a teleport and is dropped.
     * The next real ping is compared with the same accepted ping, so a burst of bad fixes
     * is dropped and the trip carries on afterwards.
     *
     * The first ping has nothing to be compared with, so it must be confirmed by the second:
     * if first-to-second is already impossible, the first ping is the bad one and is dropped.
     * (Limitation: two or more consistent bad fixes at the very start cannot be told apart
     * from the real start of the trip.)
     */
    private List<Ping> dropTeleports(List<Ping> pings) {
        if (pings.size() <= 1) {
            return pings;
        }

        int seed = 0;
        while (seed < pings.size() - 1 && isTooFast(pings.get(seed), pings.get(seed + 1))) {
            seed++;
        }

        List<Ping> accepted = new ArrayList<>(pings.size());
        accepted.add(pings.get(seed));
        for (int i = seed + 1; i < pings.size(); i++) {
            Ping lastAccepted = accepted.get(accepted.size() - 1);
            if (!isTooFast(lastAccepted, pings.get(i))) {
                accepted.add(pings.get(i));
            }
        }
        return accepted;
    }

    /** Pings are unique per timestamp by now, so the time difference is always above zero. */
    private boolean isTooFast(Ping from, Ping to) {
        double seconds = Duration.between(from.time(), to.time()).toMillis() / 1000.0;
        double meters = Haversine.distanceM(from.point(), to.point());
        return Haversine.speedKmh(meters, seconds) > config.maxSpeedKmh();
    }

    /** A missing, negative or NaN accuracy is treated as not trustworthy. */
    private boolean isAccurateEnough(Ping ping) {
        double accuracy = ping.accuracyM();
        return accuracy >= 0 && accuracy <= config.maxAccuracyM();
    }
}

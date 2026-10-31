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
 * Raw device stream in, clean time-ordered pings out. Order matters: trip window, bad
 * coordinates, low accuracy, then sort + dedupe, and teleports last.
 */
public class PingCleaner {

    /**
     * Sorting by this also settles duplicates: same timestamp, best accuracy wins, lat/lon break
     * exact ties. The winner only depends on the data, so shuffled input gives the same result.
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
     * Compares each ping with the last one we kept; if getting there needs more than the speed
     * limit it is a teleport and gets dropped. A burst of bad fixes is skipped the same way
     * because the next good ping is still compared with the last good one.
     *
     * The first ping has nothing before it, so the second one has to back it up. If first to
     * second is already impossible, the first was the bad one. (Two or more consistent bad fixes
     * right at the start can't be told from the real start. Known limit.)
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

    /** Timestamps are unique by now, so the time difference is never zero. */
    private boolean isTooFast(Ping from, Ping to) {
        double seconds = Duration.between(from.time(), to.time()).toMillis() / 1000.0;
        double meters = Haversine.distanceM(from.point(), to.point());
        return Haversine.speedKmh(meters, seconds) > config.maxSpeedKmh();
    }

    /** Missing, negative or NaN accuracy counts as untrustworthy. */
    private boolean isAccurateEnough(Ping ping) {
        double accuracy = ping.accuracyM();
        return accuracy >= 0 && accuracy <= config.maxAccuracyM();
    }
}

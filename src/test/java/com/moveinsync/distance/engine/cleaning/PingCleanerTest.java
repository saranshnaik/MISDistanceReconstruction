package com.moveinsync.distance.engine.cleaning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.RuleConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PingCleanerTest {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final Instant END = Instant.parse("2026-03-01T09:00:00Z");

    private final PingCleaner cleaner = new PingCleaner(RuleConfig.defaults());

    private static Ping ping(int secondsAfterStart, double lat, double lon, double accuracy) {
        return new Ping(START.plusSeconds(secondsAfterStart), new GeoPoint(lat, lon), accuracy);
    }

    private CleanedPings clean(List<Ping> pings) {
        return cleaner.clean(pings, START, END);
    }

    @Test
    void sortsOutOfOrderPings() {
        CleanedPings result = clean(List.of(
                ping(30, 12.93, 77.61, 10), ping(10, 12.91, 77.61, 10), ping(20, 12.92, 77.61, 10)));

        assertEquals(List.of(START.plusSeconds(10), START.plusSeconds(20), START.plusSeconds(30)),
                result.pings().stream().map(Ping::time).toList());
    }

    @Test
    void duplicateTimestampKeepsTheMostAccuratePing() {
        CleanedPings result = clean(List.of(ping(10, 12.95, 77.61, 30), ping(10, 12.91, 77.61, 5)));

        assertEquals(1, result.pings().size());
        assertEquals(5, result.pings().get(0).accuracyM());
        assertEquals(1, result.report().duplicates());
    }

    @Test
    void exactDuplicatesCollapseToOne() {
        CleanedPings result = clean(List.of(ping(10, 12.91, 77.61, 5), ping(10, 12.91, 77.61, 5)));

        assertEquals(1, result.pings().size());
    }

    @Test
    void accuracyThresholdIsInclusive() {
        CleanedPings result = clean(List.of(ping(10, 12.91, 77.61, 50.0), ping(20, 12.92, 77.61, 50.1)));

        assertEquals(1, result.pings().size());
        assertEquals(1, result.report().lowAccuracy());
    }

    @Test
    void missingOrNegativeAccuracyIsDropped() {
        CleanedPings result = clean(List.of(
                ping(10, 12.91, 77.61, Double.NaN), ping(20, 12.92, 77.61, -1)));

        assertEquals(0, result.pings().size());
        assertEquals(2, result.report().lowAccuracy());
    }

    @Test
    void dropsPingsOutsideTheTripWindow() {
        CleanedPings result = clean(List.of(
                ping(-1, 12.90, 77.61, 10), ping(0, 12.91, 77.61, 10),
                ping(3600, 12.92, 77.61, 10), ping(3601, 12.93, 77.61, 10)));

        assertEquals(2, result.pings().size());
        assertEquals(2, result.report().outsideTripWindow());
    }

    @Test
    void dropsInvalidCoordinates() {
        CleanedPings result = clean(List.of(
                ping(10, 0, 0, 10), ping(20, 95, 77.61, 10), ping(30, 12.93, 77.61, 10)));

        assertEquals(1, result.pings().size());
        assertEquals(2, result.report().invalidCoordinates());
    }

    @Test
    void everyReceivedPingIsAccountedFor() {
        CleanedPings result = clean(List.of(
                ping(-5, 12.90, 77.61, 10), ping(10, 0, 0, 10), ping(20, 12.92, 77.61, 99),
                ping(30, 12.93, 77.61, 10), ping(30, 12.93, 77.61, 10), ping(40, 12.94, 77.61, 10)));

        CleaningReport r = result.report();
        assertEquals(6, r.received());
        assertEquals(r.received(),
                r.outsideTripWindow() + r.invalidCoordinates() + r.lowAccuracy() + r.duplicates() + r.kept());
    }

    @Test
    void shuffledInputGivesTheSameOutput() {
        List<Ping> pings = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            pings.add(ping(i * 5, 12.90 + i * 0.0001, 77.60, 5 + (i % 7)));
            if (i % 10 == 0) {
                pings.add(ping(i * 5, 12.90 + i * 0.0001, 77.60, 20)); // duplicate with worse accuracy
            }
        }
        List<Ping> expected = clean(pings).pings();

        for (long seed = 1; seed <= 5; seed++) {
            List<Ping> shuffled = new ArrayList<>(pings);
            Collections.shuffle(shuffled, new Random(seed));
            assertEquals(expected, clean(shuffled).pings());
        }
    }

    @Test
    void doesNotModifyTheInputList() {
        List<Ping> input = new ArrayList<>(List.of(ping(30, 12.93, 77.61, 10), ping(10, 12.91, 77.61, 10)));
        List<Ping> copy = new ArrayList<>(input);

        clean(input);

        assertEquals(copy, input);
    }
}

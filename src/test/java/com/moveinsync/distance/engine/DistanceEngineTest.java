package com.moveinsync.distance.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.geo.Millimetres;
import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.RiderType;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.Segment;
import com.moveinsync.distance.engine.model.SegmentRule;
import com.moveinsync.distance.engine.model.StopEvent;
import com.moveinsync.distance.engine.model.TripEvidence;
import com.moveinsync.distance.engine.model.TripType;
import com.moveinsync.distance.engine.routing.RoutingProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The cab drives due north at 10 m/s (36 km/h) for 50 minutes, so the "true" distance is 30 km.
 * Pings arrive every 10 s except for a gap between 08:10 and 08:32, where two employees were
 * dropped (anchors). The stub provider says roads are 30% longer than the straight line.
 */
class DistanceEngineTest {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final int TRIP_SECONDS = 3000;
    private static final GeoPoint ORIGIN = new GeoPoint(12.9352, 77.6245);
    private static final double METERS_PER_DEGREE = 111_195.08;

    /** Counts how many times the engine had to ask for a driving distance. */
    private static final class StubRouting implements RoutingProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public double drivingDistanceM(GeoPoint from, GeoPoint to) {
            calls.incrementAndGet();
            return Haversine.distanceM(from, to) * 1.3;
        }
    }

    private static GeoPoint northBy(double meters) {
        return new GeoPoint(ORIGIN.lat() + meters / METERS_PER_DEGREE, ORIGIN.lon());
    }

    private static Ping pingAtSecond(int second) {
        return new Ping(START.plusSeconds(second), northBy(second * 10.0), 5);
    }

    private static List<Ping> pings(int fromSecond, int toSecond) {
        List<Ping> list = new ArrayList<>();
        for (int s = fromSecond; s <= toSecond; s += 10) {
            list.add(pingAtSecond(s));
        }
        return list;
    }

    private static EmployeeStop drop(String ref, int second) {
        return new EmployeeStop(ref, RiderType.PLANNED, StopEvent.DROP,
                northBy(second * 10.0), START.plusSeconds(second), null, null, null, null);
    }

    private static TripEvidence trip(List<Ping> pings, List<EmployeeStop> stops) {
        return new TripEvidence(TripType.LOGOUT, START, START.plusSeconds(TRIP_SECONDS),
                null, null, ORIGIN, pings, stops);
    }

    private static List<Ping> pingsWithUnderpassGap() {
        List<Ping> all = pings(0, 600);
        all.addAll(pings(1920, TRIP_SECONDS));
        return all;
    }

    private static TripEvidence underpassTrip() {
        return trip(pingsWithUnderpassGap(), List.of(drop("E1", 900), drop("E2", 1500)));
    }

    private static long count(TripDistanceResult result, SegmentRule rule) {
        return result.segments().stream().filter(s -> s.rule() == rule).count();
    }

    @Test
    void gapWithTwoDropsIsRoutedInThreePiecesAndEverythingElseIsStraightLine() {
        StubRouting routing = new StubRouting();
        TripDistanceResult result = new DistanceEngine(RuleConfig.defaults(), routing).compute(underpassTrip());

        assertEquals(3, count(result, SegmentRule.ROUTED));
        assertEquals(result.segments().size() - 3, count(result, SegmentRule.SHORT_GAP_AERIAL));
        assertEquals(3, routing.calls.get());

        // 16.8 km driven under normal pings + 13.2 km of gap, which the stub says is 30% longer
        assertEquals(33_960_000, result.totalMm(), 1_000);
        assertEquals(new BigDecimal("33.96"), result.billableKm());
    }

    @Test
    void naiveSumOfPingsUnderpaysComparedToTheRebuiltDistance() {
        TripDistanceResult result = new DistanceEngine(RuleConfig.defaults(), new StubRouting())
                .compute(underpassTrip());

        assertEquals(new BigDecimal("30.00"), result.naiveKm());
        assertTrue(result.billableKm().compareTo(result.naiveKm()) > 0);
    }

    @Test
    void routingIsNotUsedWhenThereAreNoRealGaps() {
        StubRouting routing = new StubRouting();
        TripDistanceResult result = new DistanceEngine(RuleConfig.defaults(), routing)
                .compute(trip(pings(0, TRIP_SECONDS), List.of()));

        assertEquals(0, routing.calls.get());
        assertEquals(0, count(result, SegmentRule.ROUTED));
        assertEquals(new BigDecimal("30.00"), result.billableKm());
    }

    @Test
    void burstOfBadFixesDoesNotChangeTheDistance() {
        DistanceEngine engine = new DistanceEngine(RuleConfig.defaults(), new StubRouting());
        TripDistanceResult clean = engine.compute(trip(pings(0, TRIP_SECONDS), List.of()));

        List<Ping> withBurst = pings(0, TRIP_SECONDS);
        GeoPoint farAway = new GeoPoint(ORIGIN.lat() + 0.08, ORIGIN.lon()); // about 9 km off
        for (int second : new int[] {1001, 1002, 1003, 1004}) {
            withBurst.add(new Ping(START.plusSeconds(second), farAway, 5));
        }
        TripDistanceResult noisy = engine.compute(trip(withBurst, List.of()));

        assertEquals(4, noisy.cleaning().teleports());
        assertEquals(clean.totalMm(), noisy.totalMm());
        assertEquals(clean.segments(), noisy.segments());
    }

    @Test
    void shuffledInputGivesExactlyTheSameResult() {
        DistanceEngine engine = new DistanceEngine(RuleConfig.defaults(), new StubRouting());
        TripDistanceResult expected = engine.compute(underpassTrip());

        for (long seed = 1; seed <= 5; seed++) {
            List<Ping> pings = new ArrayList<>(pingsWithUnderpassGap());
            pings.addAll(pings(100, 200)); // plenty of exact duplicates too
            Collections.shuffle(pings, new Random(seed));
            List<EmployeeStop> stops = new ArrayList<>(List.of(drop("E1", 900), drop("E2", 1500)));
            Collections.shuffle(stops, new Random(seed));

            assertEquals(expected.totalMm(), engine.compute(trip(pings, stops)).totalMm());
            assertEquals(expected.segments(), engine.compute(trip(pings, stops)).segments());
        }
    }

    @Test
    void runningTheSameTripTwiceGivesTheSameResult() {
        DistanceEngine engine = new DistanceEngine(RuleConfig.defaults(), new StubRouting());

        assertEquals(engine.compute(underpassTrip()), engine.compute(underpassTrip()));
    }

    @Test
    void rejectedSegmentsBillZeroButStayInTheOutput() {
        RoutingProvider absurd = (from, to) -> 150_000; // every route is 150 km
        TripDistanceResult result = new DistanceEngine(RuleConfig.defaults(), absurd).compute(underpassTrip());

        assertEquals(3, count(result, SegmentRule.REJECTED_DISTANCE));
        assertEquals(16_800_000, result.totalMm(), 1_000); // only the normal pings count
    }

    @Test
    void totalIsTheSumOfBilledSegmentsAndKmIsRoundedOnce() {
        TripDistanceResult result = new DistanceEngine(RuleConfig.defaults(), new StubRouting())
                .compute(underpassTrip());

        long sum = result.segments().stream().mapToLong(Segment::billedMm).sum();
        assertEquals(sum, result.totalMm());
        assertEquals(Millimetres.toKm(sum), result.billableKm());
    }

    @Test
    void segmentsFormOneUnbrokenChain() {
        TripDistanceResult result = new DistanceEngine(RuleConfig.defaults(), new StubRouting())
                .compute(underpassTrip());

        for (int i = 1; i < result.segments().size(); i++) {
            assertEquals(result.segments().get(i - 1).to(), result.segments().get(i).from());
        }
    }

    @Test
    void tripWithoutAnyPingsBillsNothing() {
        TripDistanceResult result = new DistanceEngine(RuleConfig.defaults(), new StubRouting())
                .compute(trip(List.of(), List.of()));

        assertEquals(0, result.segments().size()); // only the office anchor exists
        assertEquals(0, result.totalMm());
        assertEquals(new BigDecimal("0.00"), result.billableKm());
    }

    @Test
    void ruleVersionIsStableAndChangesWithAnyThreshold() {
        String a = new DistanceEngine(RuleConfig.defaults(), new StubRouting()).ruleVersion();
        String again = new DistanceEngine(RuleConfig.defaults(), new StubRouting()).ruleVersion();
        String other = new DistanceEngine(RuleConfig.defaults().withMaxSpeedKmh(100), new StubRouting()).ruleVersion();

        assertEquals(a, again);
        assertNotEquals(a, other);
        assertTrue(a.startsWith("v1-"));
    }

    @Test
    void resultCarriesTheRuleVersion() {
        DistanceEngine engine = new DistanceEngine(RuleConfig.defaults(), new StubRouting());

        assertEquals(engine.ruleVersion(), engine.compute(underpassTrip()).ruleVersion());
    }
}

package com.moveinsync.distance.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.PointSource;
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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two examples from the case study, recreated with made-up but realistic numbers.
 *
 * Example 1: a 31 km logout trip loses GPS for 22 minutes (underpass and basement). Two
 * employees were dropped during the gap. The naive sum of the pings under-pays; the engine
 * anchors the two drops, routes only the real gaps and gets back to ~31 km, with every segment
 * labelled.
 *
 * Example 2: a burst of pings 9 km away for 4 seconds is a bad fix, so it is dropped and the
 * trip distance does not change.
 *
 * The cab drives 15 km north, turns right and drives 16 km east, at a steady 10 m/s (36 km/h).
 * The turn happens inside the gap, which is why simply chaining the pings cuts the corner.
 * The road network is a stub that knows the true distance along that route.
 */
class CaseStudyExamplesTest {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final int TRIP_SECONDS = 3100;
    private static final double LEG_NORTH_M = 15_000;
    private static final double METERS_PER_DEGREE_LAT = 111_195.08;

    private static final GeoPoint ORIGIN = new GeoPoint(12.9000, 77.5000);
    private static final GeoPoint CORNER = new GeoPoint(ORIGIN.lat() + LEG_NORTH_M / METERS_PER_DEGREE_LAT, ORIGIN.lon());

    /** Where the cab is after driving this many metres along the route. */
    private static GeoPoint onRoute(double metersDriven) {
        if (metersDriven <= LEG_NORTH_M) {
            return new GeoPoint(ORIGIN.lat() + metersDriven / METERS_PER_DEGREE_LAT, ORIGIN.lon());
        }
        double east = metersDriven - LEG_NORTH_M;
        double metersPerDegreeLon = METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(CORNER.lat()));
        return new GeoPoint(CORNER.lat(), CORNER.lon() + east / metersPerDegreeLon);
    }

    /** Distance driven along the route to reach a point that lies on it. */
    private static double alongRoute(GeoPoint p) {
        boolean onNorthLeg = p.lon() == ORIGIN.lon();
        return onNorthLeg ? Haversine.distanceM(ORIGIN, p) : LEG_NORTH_M + Haversine.distanceM(CORNER, p);
    }

    /** The road network: driving distance is how far apart the two points are along the route. */
    private static final RoutingProvider ROADS =
            (from, to) -> Math.abs(alongRoute(to) - alongRoute(from));

    private static Ping pingAtSecond(int second) {
        return new Ping(START.plusSeconds(second), onRoute(second * 10.0), 5);
    }

    private static EmployeeStop drop(String ref, int second) {
        return new EmployeeStop(ref, RiderType.PLANNED, StopEvent.DROP,
                onRoute(second * 10.0), START.plusSeconds(second), null, null, null, null);
    }

    /** Pings every 10 s, except the 22 minutes between 08:15 and 08:37. */
    private static TripEvidence underpassTrip(List<Ping> extraPings) {
        List<Ping> pings = new ArrayList<>(extraPings);
        for (int s = 0; s <= 900; s += 10) {
            pings.add(pingAtSecond(s));
        }
        for (int s = 2220; s <= TRIP_SECONDS; s += 10) {
            pings.add(pingAtSecond(s));
        }
        // the office is where a logout trip starts
        return new TripEvidence(TripType.LOGOUT, START, START.plusSeconds(TRIP_SECONDS), null, null, ORIGIN,
                pings, List.of(drop("E1", 1200), drop("E2", 1700)));
    }

    private final DistanceEngine engine = new DistanceEngine(RuleConfig.defaults(), ROADS);

    @Test
    void example1_underpassTripIsRebuiltToAbout31Km() {
        TripDistanceResult result = engine.compute(underpassTrip(List.of()));

        assertEquals(TripStatus.OK, result.status());
        double km = result.billableKm().doubleValue();
        assertTrue(km > 30.95 && km < 31.05, "rebuilt distance was " + km);
    }

    @Test
    void example1_theNaiveSumOfPingsIsWellBelowWhatWasDriven() {
        TripDistanceResult result = engine.compute(underpassTrip(List.of()));

        double naive = result.naiveKm().doubleValue();
        assertTrue(naive > 26.5 && naive < 28.0, "naive sum was " + naive);
        assertTrue(result.billableKm().subtract(result.naiveKm()).compareTo(new BigDecimal("3.00")) > 0);
    }

    @Test
    void example1_onlyTheRealGapsAreRoutedAndEachSegmentCarriesItsRule() {
        TripDistanceResult result = engine.compute(underpassTrip(List.of()));

        List<Segment> routed = result.segments().stream().filter(s -> s.rule() == SegmentRule.ROUTED).toList();
        assertEquals(3, routed.size());
        assertEquals(new RoutingUsage(3, 3, 0), result.routing());

        // last ping before the gap -> drop 1 -> drop 2 -> first ping after the gap
        assertEquals(PointSource.GPS_PING, routed.get(0).from().source());
        assertEquals(PointSource.EMPLOYEE_ACTUAL, routed.get(0).to().source());
        assertEquals("E1", routed.get(0).to().ref());
        assertEquals("E1", routed.get(1).from().ref());
        assertEquals("E2", routed.get(1).to().ref());
        assertEquals("E2", routed.get(2).from().ref());
        assertEquals(PointSource.GPS_PING, routed.get(2).to().source());

        long shortGaps = result.segments().stream().filter(s -> s.rule() == SegmentRule.SHORT_GAP_AERIAL).count();
        assertEquals(result.segments().size() - 3, shortGaps);
    }

    @Test
    void example2_burstOfPingsNineKmAwayForFourSecondsChangesNothing() {
        TripDistanceResult without = engine.compute(underpassTrip(List.of()));

        GeoPoint farAway = new GeoPoint(onRoute(3_000).lat() + 0.081, onRoute(3_000).lon()); // ~9 km off
        List<Ping> burst = List.of(
                new Ping(START.plusSeconds(301), farAway, 5),
                new Ping(START.plusSeconds(302), farAway, 5),
                new Ping(START.plusSeconds(303), farAway, 5),
                new Ping(START.plusSeconds(305), farAway, 5));
        TripDistanceResult with = engine.compute(underpassTrip(burst));

        assertEquals(4, with.cleaning().teleports());
        assertEquals(without.totalMm(), with.totalMm());
        assertEquals(without.billableKm(), with.billableKm());
        assertEquals(without.segments(), with.segments());
    }
}

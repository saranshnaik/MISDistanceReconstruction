package com.moveinsync.distance.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.DistanceEngine;
import com.moveinsync.distance.engine.TripDistanceResult;
import com.moveinsync.distance.engine.TripStatus;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.SegmentRule;
import com.moveinsync.distance.engine.model.TripEvidence;
import com.moveinsync.distance.engine.routing.CachingRouteResolver;
import com.moveinsync.distance.engine.routing.SimulatedRoutingProvider;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SyntheticTripsTest {

    private final SyntheticTrips generator = new SyntheticTrips(42);

    private DistanceEngine newEngine() {
        return new DistanceEngine(RuleConfig.defaults(), new CachingRouteResolver(new SimulatedRoutingProvider()));
    }

    // ---- the generator itself ----

    @Test
    void sameSeedAndIndexGiveExactlyTheSameTrip() {
        assertEquals(generator.trip(7), new SyntheticTrips(42).trip(7));
    }

    @Test
    void differentSeedsOrIndexesGiveDifferentTrips() {
        assertNotEquals(generator.trip(7), new SyntheticTrips(43).trip(7));
        assertNotEquals(generator.trip(7).evidence(), generator.trip(8).evidence());
    }

    @Test
    void tripsAverageAboutFifteenHundredPings() {
        long total = 0;
        for (int i = 0; i < 80; i++) {
            total += generator.trip(i).evidence().pings().size();
        }
        double average = total / 80.0;
        assertTrue(average > 1_200 && average < 1_700, "average was " + average);
    }

    @Test
    void tripsOnTheSameRouteShareItsPath() {
        SyntheticTrip a = generator.trip(3);
        SyntheticTrip b = generator.trip(3 + generator.routeCount());

        assertEquals(a.routeId(), b.routeId());
        assertEquals(a.trueKm(), b.trueKm());
        assertEquals(a.evidence().office(), b.evidence().office());
        assertNotEquals(a.evidence().pings(), b.evidence().pings()); // but not the same noise
    }

    @Test
    void theMessIsReallyThere() {
        boolean duplicateTimestamps = false;
        boolean lowAccuracy = false;
        boolean outsideWindow = false;
        boolean bursts = false;
        boolean unsorted = false;
        for (int i = 0; i < 60; i++) {
            SyntheticTrip trip = generator.trip(i);
            TripEvidence e = trip.evidence();
            Set<java.time.Instant> times = new HashSet<>();
            Ping previous = null;
            for (Ping ping : e.pings()) {
                duplicateTimestamps |= !times.add(ping.time());
                lowAccuracy |= ping.accuracyM() > 50;
                outsideWindow |= ping.time().isBefore(e.tripStart());
                unsorted |= previous != null && ping.time().isBefore(previous.time());
                previous = ping;
            }
            bursts |= trip.hasBurst();
        }
        assertTrue(duplicateTimestamps && lowAccuracy && outsideWindow && bursts && unsorted);
    }

    // ---- the engine on this data ----

    @Test
    void theEngineLandsNearTheTrueDistanceDespiteTheMessAndTheGaps() {
        DistanceEngine engine = newEngine();
        for (int i = 0; i < 20; i++) {
            SyntheticTrip trip = generator.trip(i);
            TripDistanceResult result = engine.compute(trip.evidence());

            double km = result.billableKm().doubleValue();
            assertEquals(TripStatus.OK, result.status());
            assertTrue(km > trip.trueKm() * 0.95 && km < trip.trueKm() * 1.25,
                    "trip " + i + ": rebuilt " + km + " km, true " + trip.trueKm() + " km");
        }
    }

    @Test
    void theBlackoutZonesAreBridgedByLongSegmentsAndAtLeastSomeNeedRouting() {
        DistanceEngine engine = newEngine();
        for (int i = 0; i < 10; i++) {
            TripDistanceResult result = engine.compute(generator.trip(i).evidence());

            // pings come every 2 s, so anything much longer than that is a piece of a blackout:
            // the first blackout is cut by two drop-offs into 3 pieces, the second has 1
            long blackoutPieces = result.segments().stream().filter(s -> s.durationMs() > 20_000).count();
            long routed = result.segments().stream().filter(s -> s.rule() == SegmentRule.ROUTED).count();
            assertEquals(4, blackoutPieces, "trip " + i);
            // a piece under 70 s is correctly priced by the cheap straight-line rule instead
            assertTrue(routed >= 1 && routed <= 4, "trip " + i + " had " + routed + " routed segments");
        }
    }

    @Test
    void badFixBurstsAreDroppedAndTheirTripsAreNotInflated() {
        DistanceEngine engine = newEngine();
        int checked = 0;
        for (int i = 0; i < 40 && checked < 6; i++) {
            SyntheticTrip trip = generator.trip(i);
            if (trip.hasBurst()) {
                TripDistanceResult result = engine.compute(trip.evidence());
                assertTrue(result.cleaning().teleports() >= 3, "trip " + i);
                checked++;
            }
        }
        assertTrue(checked >= 3, "not enough trips with a burst to check");
    }

    @Test
    void arrivalOrderOfThePingsNeverChangesTheAnswer() {
        DistanceEngine engine = newEngine();
        for (int i = 0; i < 5; i++) {
            TripEvidence shuffled = generator.trip(i).evidence();
            List<Ping> sorted = shuffled.pings().stream().sorted(Comparator.comparing(Ping::time)).toList();
            TripEvidence ordered = new TripEvidence(shuffled.type(), shuffled.tripStart(), shuffled.tripEnd(),
                    shuffled.actualStartPoint(), shuffled.actualEndPoint(), shuffled.office(), sorted, shuffled.stops());

            assertEquals(engine.compute(shuffled).totalMm(), engine.compute(ordered).totalMm());
        }
    }

    @Test
    void sharedRoutesMeanTheCacheStartsHelpingAfterTheFirstLap() {
        SimulatedRoutingProvider provider = new SimulatedRoutingProvider();
        DistanceEngine engine = new DistanceEngine(RuleConfig.defaults(), new CachingRouteResolver(provider));

        for (int i = 0; i < generator.routeCount(); i++) {
            engine.compute(generator.trip(i).evidence()); // first lap: every route is new
        }
        long callsAfterFirstLap = provider.callCount();
        for (int i = generator.routeCount(); i < 2 * generator.routeCount(); i++) {
            engine.compute(generator.trip(i).evidence()); // second lap: same routes again
        }
        long callsInSecondLap = provider.callCount() - callsAfterFirstLap;

        assertTrue(callsInSecondLap < callsAfterFirstLap, "first lap " + callsAfterFirstLap
                + " calls, second lap " + callsInSecondLap);
    }
}

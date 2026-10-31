package com.moveinsync.distance.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.DistanceEngine;
import com.moveinsync.distance.engine.TripDistanceResult;
import com.moveinsync.distance.engine.TripStatus;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.routing.CircuitBreaker;
import com.moveinsync.distance.engine.routing.CircuitBreaker.State;
import com.moveinsync.distance.engine.routing.CircuitBreakingRouteResolver;
import com.moveinsync.distance.engine.routing.DirectRouteResolver;
import com.moveinsync.distance.engine.routing.MutableClock;
import com.moveinsync.distance.engine.routing.SimulatedRoutingProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Failure injection at scale: the provider dies halfway through a big run. Nothing may be
 * lost or throw, trips that need no routing are unaffected, trips that do are marked
 * DEGRADED, and the breaker stops further calls once it trips. After recovery, recomputing
 * the degraded trips must give EXACTLY what a no-outage run gives, millimetre for millimetre.
 *
 * Routing is uncached here on purpose: with a warm cache an outage can go unnoticed, which
 * is nice in production but would make this test prove nothing.
 */
class ProviderFailsHalfwayTest {

    private static final RuleConfig CONFIG = RuleConfig.defaults();
    private static final int BREAKER_THRESHOLD = 5;

    @Test
    void twoThousandTripsWithTheProviderDyingAtTripOneThousand() {
        runScenario(2_000);
    }

    /** Same scenario at the case study's full size. Run with: ./mvnw test -Pbenchmark */
    @Test
    @Tag("benchmark")
    void tenThousandTripsWithTheProviderDyingAtTripFiveThousand() {
        runScenario(10_000);
    }

    private void runScenario(int trips) {
        SyntheticTrips generator = new SyntheticTrips(7);
        int half = trips / 2;

        // what the answers SHOULD be: a run where the provider never fails
        DistanceEngine healthy = new DistanceEngine(CONFIG, new DirectRouteResolver(new SimulatedRoutingProvider()));
        long[] expectedMm = new long[trips];
        boolean[] needsRouting = new boolean[trips];
        for (int i = 0; i < trips; i++) {
            TripDistanceResult result = healthy.compute(generator.trip(i).evidence());
            expectedMm[i] = result.totalMm();
            needsRouting[i] = result.routing().routeRequests() > 0;
        }

        // the run with the failure
        SimulatedRoutingProvider provider = new SimulatedRoutingProvider();
        MutableClock clock = new MutableClock(Instant.parse("2026-03-02T00:00:00Z"));
        CircuitBreaker breaker = new CircuitBreaker(BREAKER_THRESHOLD, Duration.ofSeconds(30), clock);
        DistanceEngine engine = new DistanceEngine(CONFIG,
                new CircuitBreakingRouteResolver(new DirectRouteResolver(provider), breaker));

        List<Integer> degraded = new ArrayList<>();
        int answered = 0;
        long callsWhenItDied = 0;
        for (int i = 0; i < trips; i++) {
            if (i == half) {
                provider.setDown(true);
                callsWhenItDied = provider.callCount();
            }
            TripDistanceResult result = engine.compute(generator.trip(i).evidence()); // never throws
            answered++;

            if (i < half || !needsRouting[i]) {
                assertEquals(TripStatus.OK, result.status(), "trip " + i);
                assertEquals(expectedMm[i], result.totalMm(), "trip " + i + " should be unaffected");
            } else {
                assertEquals(TripStatus.DEGRADED, result.status(), "trip " + i);
                degraded.add(i);
            }
        }
        long callsDuringOutage = provider.callCount() - callsWhenItDied;

        // nothing lost
        assertEquals(trips, answered);
        // exactly the trips that needed routing after the failure were degraded
        int expectedDegraded = 0;
        for (int i = half; i < trips; i++) {
            if (needsRouting[i]) {
                expectedDegraded++;
            }
        }
        assertEquals(expectedDegraded, degraded.size());
        assertTrue(degraded.size() > (trips - half) * 0.9, "most trips need routing, got " + degraded.size());
        // the breaker stopped the hammering: only the first few attempts reached the dead provider
        assertEquals(State.OPEN, breaker.state());
        assertEquals(1, breaker.timesOpened());
        assertEquals(BREAKER_THRESHOLD, callsDuringOutage, "calls attempted while the provider was down");

        // recovery: provider is back and the breaker's wait is over, recompute what was degraded
        provider.setDown(false);
        clock.advance(Duration.ofSeconds(31));
        for (int i : degraded) {
            TripDistanceResult recomputed = engine.compute(generator.trip(i).evidence());
            assertEquals(TripStatus.OK, recomputed.status(), "trip " + i);
            assertEquals(expectedMm[i], recomputed.totalMm(), "trip " + i + " must match the no-outage answer");
        }
        assertEquals(State.CLOSED, breaker.state());
    }
}

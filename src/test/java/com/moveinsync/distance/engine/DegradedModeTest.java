package com.moveinsync.distance.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.geo.Haversine;
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
import com.moveinsync.distance.engine.routing.CachingRouteResolver;
import com.moveinsync.distance.engine.routing.CircuitBreaker;
import com.moveinsync.distance.engine.routing.CircuitBreaker.State;
import com.moveinsync.distance.engine.routing.CircuitBreakingRouteResolver;
import com.moveinsync.distance.engine.routing.MutableClock;
import com.moveinsync.distance.engine.routing.RouteResolver;
import com.moveinsync.distance.engine.routing.RoutingUnavailableException;
import com.moveinsync.distance.engine.routing.SimulatedRoutingProvider;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Same cab, same underpass as DistanceEngineTest, but now the routing provider is in trouble. */
class DegradedModeTest {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final int TRIP_SECONDS = 3000;
    private static final GeoPoint ORIGIN = new GeoPoint(12.9352, 77.6245);
    private static final double METERS_PER_DEGREE = 111_195.08;

    private static GeoPoint northBy(double meters) {
        return new GeoPoint(ORIGIN.lat() + meters / METERS_PER_DEGREE, ORIGIN.lon());
    }

    private static EmployeeStop drop(String ref, int second) {
        return new EmployeeStop(ref, RiderType.PLANNED, StopEvent.DROP,
                northBy(second * 10.0), START.plusSeconds(second), null, null, null, null);
    }

    private static TripEvidence underpassTrip() {
        List<Ping> pings = new ArrayList<>();
        for (int s = 0; s <= 600; s += 10) {
            pings.add(new Ping(START.plusSeconds(s), northBy(s * 10.0), 5));
        }
        for (int s = 1920; s <= TRIP_SECONDS; s += 10) {
            pings.add(new Ping(START.plusSeconds(s), northBy(s * 10.0), 5));
        }
        return new TripEvidence(TripType.LOGOUT, START, START.plusSeconds(TRIP_SECONDS), null, null, ORIGIN,
                pings, List.of(drop("E1", 900), drop("E2", 1500)));
    }

    private static long count(TripDistanceResult result, SegmentRule rule) {
        return result.segments().stream().filter(s -> s.rule() == rule).count();
    }

    @Test
    void healthyTripIsMarkedOk() {
        TripDistanceResult result = new DistanceEngine(RuleConfig.defaults(), new SimulatedRoutingProvider())
                .compute(underpassTrip());

        assertEquals(TripStatus.OK, result.status());
    }

    @Test
    void whenRoutingIsDownGapsAreEstimatedAt1Point4TimesTheStraightLine() {
        RouteResolver down = pairs -> {
            throw new RoutingUnavailableException("down");
        };
        TripDistanceResult result = new DistanceEngine(RuleConfig.defaults(), down).compute(underpassTrip());

        assertEquals(TripStatus.DEGRADED, result.status());
        assertEquals(3, count(result, SegmentRule.DEGRADED_ESTIMATE));
        assertEquals(0, count(result, SegmentRule.ROUTED));
        for (Segment s : result.segments()) {
            if (s.rule() == SegmentRule.DEGRADED_ESTIMATE) {
                double aerialM = Haversine.distanceM(s.from().point(), s.to().point());
                assertEquals(aerialM * 1.4 * 1000, s.billedMm(), 1.0);
            }
        }
        // 16.8 km of normal driving + 13.2 km of gaps x 1.4 = 35.28 km
        assertEquals(new BigDecimal("35.28"), result.billableKm());
    }

    @Test
    void tripsKeepWorkingWhileTheCircuitIsOpenAndNothingHitsTheProvider() {
        SimulatedRoutingProvider provider = new SimulatedRoutingProvider();
        provider.setDown(true);
        MutableClock clock = new MutableClock(Instant.parse("2026-03-02T00:00:00Z"));
        CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(30), clock);
        DistanceEngine engine = new DistanceEngine(RuleConfig.defaults(),
                new CircuitBreakingRouteResolver(new CachingRouteResolver(provider), breaker));

        for (int i = 0; i < 5; i++) {
            assertEquals(TripStatus.DEGRADED, engine.compute(underpassTrip()).status());
        }
        long callsWhenCircuitOpened = provider.callCount();
        TripDistanceResult whileOpen = engine.compute(underpassTrip());

        assertEquals(State.OPEN, breaker.state());
        assertEquals(TripStatus.DEGRADED, whileOpen.status());
        assertEquals(callsWhenCircuitOpened, provider.callCount());
    }

    @Test
    void recomputeAfterRecoveryGivesExactlyTheNormalAnswer() {
        SimulatedRoutingProvider provider = new SimulatedRoutingProvider();
        MutableClock clock = new MutableClock(Instant.parse("2026-03-02T00:00:00Z"));
        CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(30), clock);
        DistanceEngine engine = new DistanceEngine(RuleConfig.defaults(),
                new CircuitBreakingRouteResolver(new CachingRouteResolver(provider), breaker));
        TripDistanceResult normal = new DistanceEngine(RuleConfig.defaults(),
                new CachingRouteResolver(new SimulatedRoutingProvider())).compute(underpassTrip());

        provider.setDown(true);
        TripDistanceResult degraded = engine.compute(underpassTrip());
        provider.setDown(false);
        clock.advance(Duration.ofSeconds(31)); // not even needed yet (circuit still closed), harmless
        TripDistanceResult recomputed = engine.compute(underpassTrip());

        assertEquals(TripStatus.DEGRADED, degraded.status());
        assertTrue(degraded.billableKm().compareTo(normal.billableKm()) != 0);
        assertEquals(TripStatus.OK, recomputed.status());
        assertEquals(normal.segments(), recomputed.segments());
        assertEquals(normal.totalMm(), recomputed.totalMm());
    }
}

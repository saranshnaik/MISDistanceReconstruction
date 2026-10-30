package com.moveinsync.distance.engine.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.routing.CircuitBreaker.State;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CircuitBreakingRouteResolverTest {

    private static final List<RoutePair> ONE_ROUTE = List.of(
            new RoutePair(new GeoPoint(12.9352, 77.6245), new GeoPoint(12.9716, 77.5946)));

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T08:00:00Z"));
    private final SimulatedRoutingProvider provider = new SimulatedRoutingProvider();
    private final CircuitBreakingRouteResolver resolver = new CircuitBreakingRouteResolver(
            new DirectRouteResolver(provider), new CircuitBreaker(5, Duration.ofSeconds(30), clock));

    private void failFiveTimes() {
        provider.setDown(true);
        for (int i = 0; i < 5; i++) {
            assertThrows(RoutingUnavailableException.class, () -> resolver.resolve(ONE_ROUTE));
        }
    }

    @Test
    void passesAnswersThroughWhileTheProviderIsHealthy() {
        RouteResolution resolution = resolver.resolve(ONE_ROUTE);

        assertEquals(1, resolution.meters().size());
        assertEquals(State.CLOSED, resolver.breaker().state());
    }

    @Test
    void afterFiveFailuresTheProviderIsNoLongerTouched() {
        failFiveTimes();
        long callsBefore = provider.callCount();

        RoutingUnavailableException refused =
                assertThrows(RoutingUnavailableException.class, () -> resolver.resolve(ONE_ROUTE));

        assertEquals(State.OPEN, resolver.breaker().state());
        assertEquals("routing circuit is open", refused.getMessage());
        assertEquals(callsBefore, provider.callCount());
    }

    @Test
    void recoversByItselfOnceTheProviderIsBack() {
        failFiveTimes();
        provider.setDown(false);
        clock.advance(Duration.ofSeconds(30));

        RouteResolution resolution = resolver.resolve(ONE_ROUTE); // the probe

        assertTrue(resolution.meters().get(0) > 0);
        assertEquals(State.CLOSED, resolver.breaker().state());
    }

    @Test
    void probeThatFailsKeepsTheCircuitOpen() {
        failFiveTimes();
        clock.advance(Duration.ofSeconds(30));

        assertThrows(RoutingUnavailableException.class, () -> resolver.resolve(ONE_ROUTE)); // probe, still down

        assertEquals(State.OPEN, resolver.breaker().state());
    }
}

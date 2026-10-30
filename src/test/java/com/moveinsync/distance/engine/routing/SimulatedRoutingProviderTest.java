package com.moveinsync.distance.engine.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.model.GeoPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulatedRoutingProviderTest {

    private static final GeoPoint A = new GeoPoint(12.9352, 77.6245);
    private static final GeoPoint B = new GeoPoint(12.9716, 77.5946);

    @Test
    void sameTwoPointsAlwaysGiveTheSameDistance() {
        SimulatedRoutingProvider provider = new SimulatedRoutingProvider();

        assertEquals(provider.drivingDistanceM(A, B), provider.drivingDistanceM(A, B));
        assertEquals(provider.drivingDistanceM(A, B), new SimulatedRoutingProvider().drivingDistanceM(A, B));
    }

    @Test
    void roadIsBetween25And45PercentLongerThanTheStraightLine() {
        SimulatedRoutingProvider provider = new SimulatedRoutingProvider();

        for (int i = 0; i < 200; i++) {
            GeoPoint from = new GeoPoint(12.90 + i * 0.001, 77.55);
            GeoPoint to = new GeoPoint(12.95 + i * 0.0007, 77.65);
            double ratio = provider.drivingDistanceM(from, to) / Haversine.distanceM(from, to);
            assertTrue(ratio >= 1.25 - 1e-9 && ratio <= 1.45 + 1e-9, "ratio was " + ratio);
        }
    }

    @Test
    void everyCallIsCounted() {
        SimulatedRoutingProvider provider = new SimulatedRoutingProvider();

        provider.drivingDistanceM(A, B);
        provider.drivingDistanceM(B, A);

        assertEquals(2, provider.callCount());
    }

    @Test
    void whenDownItThrowsButStillCountsTheAttempt() {
        SimulatedRoutingProvider provider = new SimulatedRoutingProvider();
        provider.setDown(true);

        assertThrows(RoutingUnavailableException.class, () -> provider.drivingDistanceM(A, B));
        assertEquals(1, provider.callCount());
    }

    @Test
    void worksAgainOnceItIsBackUp() {
        SimulatedRoutingProvider provider = new SimulatedRoutingProvider();
        provider.setDown(true);
        provider.setDown(false);

        assertTrue(provider.drivingDistanceM(A, B) > 0);
    }

    @Test
    void latencyMakesCallsSlower() {
        SimulatedRoutingProvider slow = new SimulatedRoutingProvider(30);

        long before = System.nanoTime();
        slow.drivingDistanceM(A, B);
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;

        assertTrue(elapsedMs >= 25, "took only " + elapsedMs + " ms");
    }

    @Test
    void aWholeBatchIsOneRequestAndKeepsTheOrder() {
        SimulatedRoutingProvider provider = new SimulatedRoutingProvider();
        List<RoutePair> pairs = List.of(new RoutePair(A, B), new RoutePair(B, A), new RoutePair(A, B));

        List<Double> answers = provider.drivingDistancesM(pairs);

        assertEquals(1, provider.callCount());
        assertEquals(3, answers.size());
        assertEquals(provider.drivingDistanceM(A, B), answers.get(0));
        assertEquals(provider.drivingDistanceM(B, A), answers.get(1));
        assertEquals(answers.get(0), answers.get(2));
    }

    @Test
    void aBatchFailsAsAWholeWhenDown() {
        SimulatedRoutingProvider provider = new SimulatedRoutingProvider();
        provider.setDown(true);

        assertThrows(RoutingUnavailableException.class,
                () -> provider.drivingDistancesM(List.of(new RoutePair(A, B))));
        assertEquals(1, provider.callCount());
    }
}

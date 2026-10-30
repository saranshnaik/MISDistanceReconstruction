package com.moveinsync.distance.engine.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.model.GeoPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class CachingRoutingProviderTest {

    // A and A2 are in the same cell, B and B2 are in the same cell, and the two cells differ
    private static final GeoPoint A = new GeoPoint(12.93521, 77.62451);
    private static final GeoPoint A2 = new GeoPoint(12.93579, 77.62499);
    private static final GeoPoint B = new GeoPoint(12.97161, 77.59461);
    private static final GeoPoint B2 = new GeoPoint(12.97199, 77.59499);

    private final SimulatedRoutingProvider provider = new SimulatedRoutingProvider();
    private final CachingRoutingProvider cached = new CachingRoutingProvider(provider);

    @Test
    void secondAskForTheSameCellsDoesNotCallTheProvider() {
        cached.drivingDistanceM(A, B);
        cached.drivingDistanceM(A, B);

        assertEquals(1, provider.callCount());
        assertEquals(1, cached.hits());
        assertEquals(1, cached.misses());
    }

    @Test
    void differentPointsInTheSameCellsShareOneEntry() {
        cached.drivingDistanceM(A, B);
        cached.drivingDistanceM(A2, B2);

        assertEquals(1, provider.callCount());
    }

    @Test
    void aCacheHitGivesExactlyTheSameNumberAsAColdMiss() {
        double firstAsk = cached.drivingDistanceM(A, B);
        double warm = cached.drivingDistanceM(A2, B2); // served from the cache
        double cold = new CachingRoutingProvider(new SimulatedRoutingProvider()).drivingDistanceM(A2, B2);

        assertEquals(firstAsk, warm);
        assertEquals(cold, warm);
    }

    @Test
    void theDirectionMattersBecauseOneWayStreetsExist() {
        cached.drivingDistanceM(A, B);
        cached.drivingDistanceM(B, A);

        assertEquals(2, provider.callCount());
    }

    @Test
    void pointsInTheSameCellUseTheStraightLineAndSkipTheProvider() {
        double meters = cached.drivingDistanceM(A, A2);

        assertEquals(Haversine.distanceM(A, A2), meters);
        assertEquals(0, provider.callCount());
    }

    @Test
    void aFailedCallIsNotCachedSoTheNextOneTriesAgain() {
        provider.setDown(true);
        assertThrows(RoutingUnavailableException.class, () -> cached.drivingDistanceM(A, B));

        provider.setDown(false);
        cached.drivingDistanceM(A, B);

        assertEquals(2, provider.callCount());
        assertEquals(1, cached.size());
    }

    @Test
    void manyThreadsAskingForTheSameRouteCauseOnlyOneProviderCall() throws Exception {
        SimulatedRoutingProvider slow = new SimulatedRoutingProvider(50); // wide window for a race
        CachingRoutingProvider shared = new CachingRoutingProvider(slow);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);

        List<Future<Double>> answers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            answers.add(pool.submit(() -> {
                startTogether.await();
                return shared.drivingDistanceM(A, B);
            }));
        }
        startTogether.countDown();

        double first = answers.get(0).get();
        for (Future<Double> answer : answers) {
            assertEquals(first, answer.get());
        }
        pool.shutdown();
        assertEquals(1, slow.callCount());
    }
}

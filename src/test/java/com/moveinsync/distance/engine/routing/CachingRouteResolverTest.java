package com.moveinsync.distance.engine.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.model.GeoPoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class CachingRouteResolverTest {

    // A and A2 are in the same cell, B and B2 are in the same cell, and the two cells differ
    private static final GeoPoint A = new GeoPoint(12.93521, 77.62451);
    private static final GeoPoint A2 = new GeoPoint(12.93579, 77.62499);
    private static final GeoPoint B = new GeoPoint(12.97161, 77.59461);
    private static final GeoPoint B2 = new GeoPoint(12.97199, 77.59499);

    private final SimulatedRoutingProvider provider = new SimulatedRoutingProvider();
    private final CachingRouteResolver resolver = new CachingRouteResolver(provider);

    private static List<RoutePair> one(GeoPoint from, GeoPoint to) {
        return List.of(new RoutePair(from, to));
    }

    /** n different routes, each starting in its own cell, all ending near B. */
    private static List<RoutePair> manyDistinctRoutes(int n) {
        List<RoutePair> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            pairs.add(new RoutePair(new GeoPoint(12.80 + i * 0.002, 77.50), B));
        }
        return pairs;
    }

    @Test
    void secondAskForTheSameCellsDoesNotCallTheProvider() {
        RouteResolution first = resolver.resolve(one(A, B));
        RouteResolution second = resolver.resolve(one(A, B));

        assertEquals(1, first.providerCalls());
        assertEquals(0, first.cacheHits());
        assertEquals(0, second.providerCalls());
        assertEquals(1, second.cacheHits());
        assertEquals(1, provider.callCount());
    }

    @Test
    void differentPointsInTheSameCellsShareOneEntry() {
        resolver.resolve(one(A, B));
        RouteResolution second = resolver.resolve(one(A2, B2));

        assertEquals(0, second.providerCalls());
        assertEquals(1, provider.callCount());
    }

    @Test
    void aCacheHitGivesExactlyTheSameNumberAsAColdMiss() {
        double firstAsk = resolver.resolve(one(A, B)).meters().get(0);
        double warm = resolver.resolve(one(A2, B2)).meters().get(0); // from the cache
        double cold = new CachingRouteResolver(new SimulatedRoutingProvider())
                .resolve(one(A2, B2)).meters().get(0);

        assertEquals(firstAsk, warm);
        assertEquals(cold, warm);
    }

    @Test
    void theDirectionMattersBecauseOneWayStreetsExist() {
        resolver.resolve(one(A, B));
        resolver.resolve(one(B, A));

        assertEquals(2, provider.callCount());
    }

    @Test
    void pointsInTheSameCellUseTheStraightLineAndSkipTheProvider() {
        RouteResolution result = resolver.resolve(one(A, A2));

        assertEquals(Haversine.distanceM(A, A2), result.meters().get(0));
        assertEquals(0, result.providerCalls());
        assertEquals(0, provider.callCount());
    }

    @Test
    void sameRouteAskedManyTimesInOneTripIsFetchedOnce() {
        RouteResolution result = resolver.resolve(List.of(
                new RoutePair(A, B), new RoutePair(A2, B2), new RoutePair(A, B)));

        assertEquals(1, result.providerCalls());
        assertEquals(1, provider.callCount());
        assertEquals(result.meters().get(0), result.meters().get(1));
        assertEquals(result.meters().get(0), result.meters().get(2));
    }

    @Test
    void sixtyNewRoutesGoOutInThreeRequests() {
        RouteResolution result = resolver.resolve(manyDistinctRoutes(60));

        assertEquals(3, result.providerCalls()); // 25 + 25 + 10
        assertEquals(3, provider.callCount());
        assertEquals(60, result.meters().size());
    }

    @Test
    void answersComeBackInTheOrderOfTheQuestions() {
        List<RoutePair> pairs = manyDistinctRoutes(40);
        List<RoutePair> reversed = new ArrayList<>(pairs);
        Collections.reverse(reversed);

        List<Double> forward = resolver.resolve(pairs).meters();
        List<Double> backward = resolver.resolve(reversed).meters();

        for (int i = 0; i < pairs.size(); i++) {
            assertEquals(forward.get(i), backward.get(pairs.size() - 1 - i));
        }
    }

    @Test
    void onlyTheUnknownRoutesAreSentWhenSomeAreCached() {
        resolver.resolve(manyDistinctRoutes(30)); // 2 requests, now cached
        RouteResolution result = resolver.resolve(manyDistinctRoutes(40)); // 10 new routes

        assertEquals(1, result.providerCalls());
        assertEquals(30, result.cacheHits());
    }

    @Test
    void aFailedRequestIsNotCachedSoTheNextOneTriesAgain() {
        provider.setDown(true);
        assertThrows(RoutingUnavailableException.class, () -> resolver.resolve(one(A, B)));
        assertEquals(0, resolver.size());

        provider.setDown(false);
        resolver.resolve(one(A, B));

        assertEquals(2, provider.callCount());
        assertEquals(1, resolver.size());
    }

    @Test
    void manyThreadsAskingForTheSameRouteCauseOnlyOneProviderCall() throws Exception {
        SimulatedRoutingProvider slow = new SimulatedRoutingProvider(50); // wide window for a race
        CachingRouteResolver shared = new CachingRouteResolver(slow);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startTogether = new CountDownLatch(1);

        List<Future<Double>> answers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            answers.add(pool.submit(() -> {
                startTogether.await();
                return shared.resolve(one(A, B)).meters().get(0);
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

package com.moveinsync.distance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.TripEvidence;
import com.moveinsync.distance.engine.model.TripType;
import com.moveinsync.distance.engine.routing.CircuitBreaker;
import com.moveinsync.distance.engine.routing.MutableClock;
import com.moveinsync.distance.engine.routing.SimulatedRoutingProvider;
import com.moveinsync.distance.observability.EngineMetrics;
import com.moveinsync.distance.persistence.DistanceState;
import com.moveinsync.distance.persistence.TripEntity;
import com.moveinsync.distance.persistence.TripRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DegradedTripRecomputeJobTest {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final double METERS_PER_DEGREE = 111_195.08;

    @Autowired
    DegradedTripRecomputeJob job;
    @Autowired
    TripService trips;
    @Autowired
    SimulatedRoutingProvider provider;

    @AfterEach
    void providerBackUp() {
        provider.setDown(false);
    }

    /** A trip with a 22-minute hole, on a stretch of map used by no other test. */
    private TripView ingestDegradedTrip(String ref) {
        GeoPoint origin = new GeoPoint(12.9352, 77.6245 + (Math.abs(ref.hashCode()) % 20_000) * 0.002);
        List<Ping> pings = new ArrayList<>();
        for (int s = 0; s <= 3000; s += 10) {
            if (s > 600 && s < 1920) {
                continue;
            }
            pings.add(new Ping(START.plusSeconds(s),
                    new GeoPoint(origin.lat() + s * 10.0 / METERS_PER_DEGREE, origin.lon()), 5));
        }
        TripEvidence evidence = new TripEvidence(TripType.LOGOUT, START, START.plusSeconds(3000),
                null, null, origin, pings, List.of());
        provider.setDown(true);
        TripView view = trips.ingest("acme", ref, evidence);
        provider.setDown(false);
        return view;
    }

    private static String newRef() {
        return "T-" + UUID.randomUUID();
    }

    @Test
    void whenRoutingIsBackDegradedTripsAreRecomputedToOk() {
        String a = newRef();
        String b = newRef();
        assertEquals(DistanceState.DEGRADED, ingestDegradedTrip(a).distanceState());
        assertEquals(DistanceState.DEGRADED, ingestDegradedTrip(b).distanceState());

        int recovered = job.runOnce();

        assertTrue(recovered >= 2, "recovered " + recovered);
        assertEquals(DistanceState.OK, trips.get("acme", a).distanceState());
        assertEquals(DistanceState.OK, trips.get("acme", b).distanceState());
    }

    @Test
    void stopsAtTheFirstTripWhenRoutingIsStillDown() {
        String a = newRef();
        String b = newRef();
        ingestDegradedTrip(a);
        ingestDegradedTrip(b);
        long callsBefore = provider.callCount();
        provider.setDown(true);

        int recovered = job.runOnce();

        assertEquals(0, recovered);
        assertTrue(provider.callCount() - callsBefore <= 1, "provider was asked "
                + (provider.callCount() - callsBefore) + " times");
        assertEquals(DistanceState.DEGRADED, trips.get("acme", a).distanceState());
        assertEquals(DistanceState.DEGRADED, trips.get("acme", b).distanceState());
    }

    @Test
    void doesNothingWhileTheCircuitIsOpen() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-02T00:00:00Z"));
        CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(30), clock);
        for (int i = 0; i < 5; i++) {
            breaker.allowRequest();
            breaker.recordFailure();
        }
        TripRepository repository = mock(TripRepository.class);
        TripService service = mock(TripService.class);
        DegradedTripRecomputeJob quietJob = new DegradedTripRecomputeJob(repository, service, breaker, new EngineMetrics(new SimpleMeterRegistry()));

        assertEquals(0, quietJob.runOnce());

        verifyNoInteractions(repository, service);
    }

    @Test
    void oneFailingTripDoesNotBlockTheOthers() {
        TripRepository repository = mock(TripRepository.class);
        TripService service = mock(TripService.class);
        CircuitBreaker closed = new CircuitBreaker(5, Duration.ofSeconds(30),
                new MutableClock(Instant.parse("2026-03-02T00:00:00Z")));
        TripEntity broken = trip("broken");
        TripEntity fine = trip("fine");
        when(repository.findTop100ByDistanceStateOrderByIdAsc(DistanceState.DEGRADED))
                .thenReturn(List.of(broken, fine));
        when(service.compute("acme", "broken")).thenThrow(new IllegalStateException("boom"));
        TripView ok = mock(TripView.class);
        when(ok.distanceState()).thenReturn(DistanceState.OK);
        when(service.compute("acme", "fine")).thenReturn(ok);

        int recovered = new DegradedTripRecomputeJob(repository, service, closed, new EngineMetrics(new SimpleMeterRegistry())).runOnce();

        assertEquals(1, recovered);
        verify(service).compute("acme", "broken");
        verify(service).compute("acme", "fine");
    }

    private static TripEntity trip(String ref) {
        return new TripEntity("acme", ref, TripType.LOGOUT, START, START.plusSeconds(60), null, null,
                new GeoPoint(12.9, 77.6), START);
    }
}

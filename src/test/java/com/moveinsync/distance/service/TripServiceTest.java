package com.moveinsync.distance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.RiderType;
import com.moveinsync.distance.engine.model.StopEvent;
import com.moveinsync.distance.engine.model.TripEvidence;
import com.moveinsync.distance.engine.model.TripType;
import com.moveinsync.distance.engine.routing.SimulatedRoutingProvider;
import com.moveinsync.distance.persistence.BillingState;
import com.moveinsync.distance.persistence.DistanceComputationRepository;
import com.moveinsync.distance.persistence.DistanceState;
import com.moveinsync.distance.persistence.PingRepository;
import com.moveinsync.distance.persistence.TripRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The service manages its own transactions, so these tests are NOT @Transactional: they hit the
 * real commit and lock behaviour. Each test uses a fresh trip ref so they do not disturb each other.
 */
@SpringBootTest
class TripServiceTest {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final int TRIP_SECONDS = 3000;
    private static final GeoPoint ORIGIN = new GeoPoint(12.9352, 77.6245);
    private static final double METERS_PER_DEGREE = 111_195.08;

    @Autowired
    TripService service;
    @Autowired
    TripRepository trips;
    @Autowired
    PingRepository pings;
    @Autowired
    DistanceComputationRepository computations;
    @Autowired
    SimulatedRoutingProvider provider;

    @AfterEach
    void providerBackUp() {
        provider.setDown(false);
    }

    /** Each trip ref drives along its own stretch of map, so the shared route cache cannot leak between tests. */
    private static GeoPoint originFor(String ref) {
        double eastShift = (Math.abs(ref.hashCode()) % 20_000) * 0.002;
        return new GeoPoint(ORIGIN.lat(), ORIGIN.lon() + eastShift);
    }

    private static GeoPoint northBy(GeoPoint origin, double meters) {
        return new GeoPoint(origin.lat() + meters / METERS_PER_DEGREE, origin.lon());
    }

    /** A 50-minute drive north with a 22-minute hole in the pings and two drops inside it. */
    private static TripEvidence underpassTrip(String ref) {
        GeoPoint origin = originFor(ref);
        List<Ping> pings = new ArrayList<>();
        for (int s = 0; s <= 600; s += 10) {
            pings.add(new Ping(START.plusSeconds(s), northBy(origin, s * 10.0), 5));
        }
        for (int s = 1920; s <= TRIP_SECONDS; s += 10) {
            pings.add(new Ping(START.plusSeconds(s), northBy(origin, s * 10.0), 5));
        }
        EmployeeStop planned = new EmployeeStop("E1", RiderType.PLANNED, StopEvent.DROP,
                northBy(origin, 9_000), START.plusSeconds(900), null, null, null, null);
        EmployeeStop adHoc = new EmployeeStop("E2", RiderType.AD_HOC, StopEvent.DROP,
                null, null, null, null, northBy(origin, 15_000), START.plusSeconds(1500));
        return new TripEvidence(TripType.LOGOUT, START, START.plusSeconds(TRIP_SECONDS), null, null, origin,
                pings, List.of(planned, adHoc));
    }

    private static String newRef() {
        return "T-" + UUID.randomUUID();
    }

    @Test
    void ingestStoresTheTripAndItsFirstComputation() {
        String ref = newRef();

        TripView view = service.ingest("acme", ref, underpassTrip(ref));

        assertEquals(DistanceState.OK, view.distanceState());
        assertEquals(BillingState.UNBILLED, view.billingState());
        assertTrue(view.billableKm().signum() > 0);
        assertTrue(view.billableKm().compareTo(view.naiveKm()) >= 0);
        assertEquals(3, view.routeRequests());
        assertEquals(1, computations.findByTripIdOrderByIdAsc(trips.findByTenantIdAndTripRef("acme", ref)
                .orElseThrow().getId()).size());
    }

    @Test
    void ingestingTheSameTripAgainChangesNothing() {
        String ref = newRef();
        TripView first = service.ingest("acme", ref, underpassTrip(ref));

        TripView second = service.ingest("acme", ref, underpassTrip(ref));

        assertEquals(first.computationId(), second.computationId());
        assertEquals(first.billableKm(), second.billableKm());
        Long tripId = trips.findByTenantIdAndTripRef("acme", ref).orElseThrow().getId();
        assertEquals(1, computations.findByTripIdOrderByIdAsc(tripId).size());
        assertEquals(underpassTrip(ref).pings().size(), pings.countByTripId(tripId));
    }

    @Test
    void recomputeWithUnchangedInputDoesNotRunTheEngineAgain() {
        String ref = newRef();
        TripView first = service.ingest("acme", ref, underpassTrip(ref));
        long callsBefore = provider.callCount();

        TripView again = service.compute("acme", ref);

        assertEquals(first.computationId(), again.computationId());
        assertEquals(callsBefore, provider.callCount());
    }

    @Test
    void duplicatePingsInThePayloadAreStoredOnce() {
        String ref = newRef();
        TripEvidence base = underpassTrip(ref);
        List<Ping> doubled = new ArrayList<>(base.pings());
        doubled.addAll(base.pings());
        TripEvidence withDuplicates = new TripEvidence(base.type(), base.tripStart(), base.tripEnd(), null, null,
                base.office(), doubled, base.stops());

        service.ingest("acme", ref, withDuplicates);

        assertEquals(base.pings().size(), pings.countByTripId(trips.findByTenantIdAndTripRef("acme", ref)
                .orElseThrow().getId()));
    }

    @Test
    void segmentsExplainTheDistanceLineByLineWithoutExposingHomeCoordinates() {
        String ref = newRef();
        service.ingest("acme", ref, underpassTrip(ref));

        List<SegmentView> segments = service.segments("acme", ref);

        assertTrue(segments.stream().anyMatch(s -> s.rule().equals("ROUTED")));
        assertTrue(segments.stream().anyMatch(s -> s.rule().equals("SHORT_GAP_AERIAL")));
        for (SegmentView s : segments) {
            if ("EMPLOYEE_HOME_SIGNIN".equals(s.fromSource())) {
                assertNull(s.fromLat());
                assertNull(s.fromLon());
            }
            if ("EMPLOYEE_HOME_SIGNIN".equals(s.toSource())) {
                assertNull(s.toLat());
                assertNull(s.toLon());
            }
        }
        assertTrue(segments.stream().anyMatch(s -> "EMPLOYEE_HOME_SIGNIN".equals(s.toSource())));
    }

    @Test
    void anotherTenantCannotSeeTheTripNotEvenThatItExists() {
        String ref = newRef();
        service.ingest("acme", ref, underpassTrip(ref));

        assertThrows(TripNotFoundException.class, () -> service.get("globex", ref));
        assertThrows(TripNotFoundException.class, () -> service.segments("globex", ref));
        assertThrows(TripNotFoundException.class, () -> service.compute("globex", ref));
    }

    @Test
    void sameRefInTwoTenantsAreTwoSeparateTrips() {
        String ref = newRef();

        TripView acme = service.ingest("acme", ref, underpassTrip(ref));
        TripView globex = service.ingest("globex", ref, underpassTrip(ref));

        assertNotEquals(acme.computationId(), globex.computationId());
    }

    @Test
    void routingDownGivesADegradedTripAndRecomputingAfterRecoveryFixesIt() {
        String ref = newRef();
        provider.setDown(true);
        TripView degraded = service.ingest("acme", ref, underpassTrip(ref));
        provider.setDown(false);

        TripView recovered = service.compute("acme", ref);

        assertEquals(DistanceState.DEGRADED, degraded.distanceState());
        assertEquals(DistanceState.OK, recovered.distanceState());
        assertNotEquals(degraded.computationId(), recovered.computationId());
        assertNotEquals(degraded.billableKm(), recovered.billableKm());
        Long tripId = trips.findByTenantIdAndTripRef("acme", ref).orElseThrow().getId();
        assertEquals(2, computations.findByTripIdOrderByIdAsc(tripId).size()); // history is kept
    }

    @Test
    void aDegradedTripIsRecomputedEveryTimeNotServedFromTheLastGuess() {
        String ref = newRef();
        provider.setDown(true);
        TripView first = service.ingest("acme", ref, underpassTrip(ref));

        TripView second = service.compute("acme", ref);

        assertNotEquals(first.computationId(), second.computationId());
        assertFalse(second.distanceState() == DistanceState.OK);
    }

    @Test
    void eightRequestsIngestingTheSameNewTripAtOnceCreateOneTripAndOneComputation() throws Exception {
        String ref = newRef();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        TripEvidence evidence = underpassTrip(ref);

        List<Future<TripView>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return service.ingest("acme", ref, evidence);
            }));
        }
        go.countDown();

        TripView first = results.get(0).get();
        for (Future<TripView> result : results) {
            assertEquals(first.computationId(), result.get().computationId());
        }
        pool.shutdown();
        Long tripId = trips.findByTenantIdAndTripRef("acme", ref).orElseThrow().getId();
        assertEquals(1, computations.findByTripIdOrderByIdAsc(tripId).size());
        assertEquals(evidence.pings().size(), pings.countByTripId(tripId));
    }
}

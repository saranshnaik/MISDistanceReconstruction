package com.moveinsync.distance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.TripEvidence;
import com.moveinsync.distance.engine.model.TripType;
import com.moveinsync.distance.engine.routing.SimulatedRoutingProvider;
import com.moveinsync.distance.persistence.BillingState;
import com.moveinsync.distance.persistence.DistanceComputationRepository;
import com.moveinsync.distance.persistence.DistanceState;
import com.moveinsync.distance.persistence.TripRepository;
import java.math.BigDecimal;
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
 * The cab drives due north at 10 m/s, so a full 3000 s trip is exactly 30 km. Each test trip
 * gets its own stretch of map so the shared route cache cannot leak between tests.
 */
@SpringBootTest
class BillingServiceTest {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final int TRIP_SECONDS = 3000;
    private static final double METERS_PER_DEGREE = 111_195.08;

    @Autowired
    TripService trips;
    @Autowired
    BillingService billing;
    @Autowired
    TripRepository tripRepository;
    @Autowired
    DistanceComputationRepository computations;
    @Autowired
    SimulatedRoutingProvider provider;

    @AfterEach
    void providerBackUp() {
        provider.setDown(false);
    }

    private static String newRef() {
        return "T-" + UUID.randomUUID();
    }

    private static GeoPoint originFor(String ref) {
        return new GeoPoint(12.9352, 77.6245 + (Math.abs(ref.hashCode()) % 20_000) * 0.002);
    }

    private static List<Ping> pingsBetween(String ref, int fromSecond, int toSecond) {
        GeoPoint origin = originFor(ref);
        List<Ping> list = new ArrayList<>();
        for (int s = fromSecond; s <= toSecond; s += 10) {
            list.add(new Ping(START.plusSeconds(s),
                    new GeoPoint(origin.lat() + s * 10.0 / METERS_PER_DEGREE, origin.lon()), 5));
        }
        return list;
    }

    private static TripEvidence trip(String ref, List<Ping> pings, List<EmployeeStop> stops) {
        return new TripEvidence(TripType.LOGOUT, START, START.plusSeconds(TRIP_SECONDS), null, null,
                originFor(ref), pings, stops);
    }

    /** Pings for the first 40 minutes only: the last 10 minutes have not reached us yet. */
    private TripView ingestShortTrip(String ref) {
        return trips.ingest("acme", ref, trip(ref, pingsBetween(ref, 0, 2400), List.of()));
    }

    // ---- closing the invoice ----

    @Test
    void billingFreezesTheCurrentDistanceOnTheInvoice() {
        String ref = newRef();
        TripView computed = ingestShortTrip(ref);

        TripView billed = billing.bill("acme", ref);

        assertEquals(BillingState.BILLED, billed.billingState());
        assertEquals(computed.billableKm(), billed.billedKm());
        assertEquals(computed.billableKm(), billed.finalBilledKm());
    }

    @Test
    void billingTwiceDoesNothingTheSecondTime() {
        String ref = newRef();
        ingestShortTrip(ref);
        TripView first = billing.bill("acme", ref);

        TripView second = billing.bill("acme", ref);

        assertEquals(first.billedKm(), second.billedKm());
        assertEquals(first.computationId(), second.computationId());
    }

    @Test
    void anEstimatedTripCannotBeBilledUntilItIsRecomputed() {
        String ref = newRef();
        provider.setDown(true);
        TripEvidence withGap = trip(ref, concat(pingsBetween(ref, 0, 600), pingsBetween(ref, 1920, 3000)), List.of());
        TripView degraded = trips.ingest("acme", ref, withGap);

        assertEquals(DistanceState.DEGRADED, degraded.distanceState());
        assertThrows(TripNotBillableException.class, () -> billing.bill("acme", ref));

        provider.setDown(false);
        trips.compute("acme", ref);

        assertEquals(BillingState.BILLED, billing.bill("acme", ref).billingState());
    }

    // ---- late pings ----

    @Test
    void latePingsAfterBillingGiveAPositiveSignedAdjustmentAndLeaveTheInvoiceAlone() {
        String ref = newRef();
        ingestShortTrip(ref);
        TripView billed = billing.bill("acme", ref);

        TripView after = billing.addLatePings("acme", ref, pingsBetween(ref, 2410, 3000));

        BillingSummary summary = billing.summary("acme", ref);
        assertEquals(1, summary.adjustments().size());
        BigDecimal delta = summary.adjustments().get(0).deltaKm();
        assertTrue(delta.signum() > 0, "delta was " + delta);
        assertEquals(new BigDecimal("30.00"), after.billableKm());
        assertEquals(billed.billedKm(), after.billedKm());                 // invoice untouched
        assertEquals(billed.billedKm().add(delta), after.finalBilledKm()); // adjustment on top
        assertEquals(new BigDecimal("30.00"), after.finalBilledKm());
    }

    @Test
    void latePingsThatMakeTheDistanceShorterGiveANegativeAdjustment() {
        String ref = newRef();
        // a 22-minute hole is routed (the simulated roads are 25-45% longer than the straight line)
        trips.ingest("acme", ref, trip(ref, concat(pingsBetween(ref, 0, 600), pingsBetween(ref, 1920, 3000)), List.of()));
        TripView billed = billing.bill("acme", ref);

        TripView after = billing.addLatePings("acme", ref, pingsBetween(ref, 610, 1910));

        BigDecimal delta = billing.summary("acme", ref).adjustments().get(0).deltaKm();
        assertTrue(delta.signum() < 0, "delta was " + delta);
        assertTrue(after.billableKm().compareTo(billed.billedKm()) < 0);
        assertEquals(new BigDecimal("30.00"), after.finalBilledKm());
    }

    @Test
    void sendingTheSameLatePingsAgainDoesNotCreateAnotherAdjustment() {
        String ref = newRef();
        ingestShortTrip(ref);
        billing.bill("acme", ref);
        List<Ping> late = pingsBetween(ref, 2410, 3000);
        TripView first = billing.addLatePings("acme", ref, late);

        TripView again = billing.addLatePings("acme", ref, late);

        assertEquals(1, billing.summary("acme", ref).adjustments().size());
        assertEquals(first.computationId(), again.computationId());
    }

    @Test
    void latePingsBeforeBillingJustUpdateTheDistance() {
        String ref = newRef();
        TripView before = ingestShortTrip(ref);

        TripView after = billing.addLatePings("acme", ref, pingsBetween(ref, 2410, 3000));

        assertTrue(after.billableKm().compareTo(before.billableKm()) > 0);
        assertNull(after.finalBilledKm());
        assertTrue(billing.summary("acme", ref).adjustments().isEmpty());
    }

    @Test
    void latePingsThatChangeNothingGiveNoAdjustment() {
        String ref = newRef();
        ingestShortTrip(ref);
        billing.bill("acme", ref);
        // a ping before the trip started is dropped by the cleaner, so the distance cannot change
        Ping outsideTheTrip = new Ping(START.minusSeconds(600), originFor(ref), 5);

        billing.addLatePings("acme", ref, List.of(outsideTheTrip));

        assertTrue(billing.summary("acme", ref).adjustments().isEmpty());
    }

    @Test
    void whileRoutingIsDownLatePingsMakeNoAdjustmentAndRecoveryMakesExactlyOne() {
        String ref = newRef();
        trips.ingest("acme", ref, trip(ref, concat(pingsBetween(ref, 0, 600), pingsBetween(ref, 1920, 3000)), List.of()));
        billing.bill("acme", ref);

        provider.setDown(true);
        TripView estimated = billing.addLatePings("acme", ref, pingsBetween(ref, 610, 1000)); // new gap, not cached
        assertEquals(DistanceState.DEGRADED, estimated.distanceState());
        assertTrue(billing.summary("acme", ref).adjustments().isEmpty()); // an estimate never touches the bill

        provider.setDown(false);
        TripView recovered = trips.compute("acme", ref);

        assertEquals(DistanceState.OK, recovered.distanceState());
        assertEquals(1, billing.summary("acme", ref).adjustments().size());
        assertEquals(recovered.billableKm(), recovered.finalBilledKm());
    }

    // ---- who may touch it ----

    @Test
    void anotherTenantCannotBillOrAddPingsOrReadAdjustments() {
        String ref = newRef();
        ingestShortTrip(ref);

        assertThrows(TripNotFoundException.class, () -> billing.bill("globex", ref));
        assertThrows(TripNotFoundException.class, () -> billing.addLatePings("globex", ref, pingsBetween(ref, 2410, 2420)));
        assertThrows(TripNotFoundException.class, () -> billing.summary("globex", ref));
        assertEquals(BillingState.UNBILLED, trips.get("acme", ref).billingState());
    }

    // ---- concurrency ----

    @Test
    void twelveRequestsSendingTheSameLatePingsAtOnceProduceExactlyOneAdjustment() throws Exception {
        String ref = newRef();
        ingestShortTrip(ref);
        billing.bill("acme", ref);
        List<Ping> late = pingsBetween(ref, 2410, 3000);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<TripView>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return billing.addLatePings("acme", ref, late);
            }));
        }
        go.countDown();
        for (Future<TripView> result : results) {
            assertEquals(new BigDecimal("30.00"), result.get().finalBilledKm());
        }
        pool.shutdown();

        assertEquals(1, billing.summary("acme", ref).adjustments().size());
        Long tripId = tripRepository.findByTenantIdAndTripRef("acme", ref).orElseThrow().getId();
        assertEquals(2, computations.findByTripIdOrderByIdAsc(tripId).size()); // original + one recompute
    }

    private static List<Ping> concat(List<Ping> a, List<Ping> b) {
        List<Ping> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}

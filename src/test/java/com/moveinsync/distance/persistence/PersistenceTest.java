package com.moveinsync.distance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.TripStatus;
import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.RiderType;
import com.moveinsync.distance.engine.model.StopEvent;
import com.moveinsync.distance.engine.model.TripType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/** Runs the real Flyway migration on H2 and checks the repositories against it. */
@SpringBootTest
@Transactional
class PersistenceTest {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final GeoPoint OFFICE = new GeoPoint(12.9352, 77.6245);

    @Autowired
    TripRepository trips;
    @Autowired
    TripStopRepository stops;
    @Autowired
    PingRepository pings;
    @Autowired
    DistanceComputationRepository computations;
    @Autowired
    BillingAdjustmentRepository adjustments;

    private TripEntity newTrip(String tenant, String ref) {
        return trips.saveAndFlush(new TripEntity(tenant, ref, TripType.LOGOUT, START, START.plusSeconds(3000),
                null, new GeoPoint(12.94, 77.62), OFFICE, START));
    }

    private DistanceComputationEntity newComputation(TripEntity trip, TripStatus status) {
        return computations.saveAndFlush(new DistanceComputationEntity(trip.getTenantId(), trip.getId(),
                "hash", "v1-abcd1234", 30_600_000, new BigDecimal("30.60"), status, 3, 1, 0, "[]", START));
    }

    @Test
    void tripRoundTripsIncludingOptionalPoints() {
        TripEntity saved = newTrip("acme", "T-1");

        TripEntity loaded = trips.findByTenantIdAndTripRef("acme", "T-1").orElseThrow();

        assertEquals(saved.getId(), loaded.getId());
        assertEquals(TripType.LOGOUT, loaded.getTripType());
        assertEquals(DistanceState.PENDING, loaded.getDistanceState());
        assertEquals(BillingState.UNBILLED, loaded.getBillingState());
        assertEquals(OFFICE, loaded.officePoint());
        assertEquals(null, loaded.actualStartPoint());
        assertEquals(new GeoPoint(12.94, 77.62), loaded.actualEndPoint());
    }

    @Test
    void sameTripRefIsAllowedForDifferentTenantsButNotWithinOne() {
        newTrip("acme", "T-1");
        newTrip("globex", "T-1");

        assertThrows(DataIntegrityViolationException.class, () -> newTrip("acme", "T-1"));
    }

    @Test
    void lookupIsAlwaysScopedToTheTenant() {
        newTrip("acme", "T-1");

        assertTrue(trips.findByTenantIdAndTripRef("globex", "T-1").isEmpty());
        assertTrue(trips.findForUpdate("globex", "T-1").isEmpty());
        assertTrue(trips.findForUpdate("acme", "T-1").isPresent());
    }

    @Test
    void stopsKeepEveryOptionalField() {
        TripEntity trip = newTrip("acme", "T-1");
        EmployeeStop stop = new EmployeeStop("E1", RiderType.AD_HOC, StopEvent.DROP,
                null, null, null, null, new GeoPoint(12.91, 77.59), START.plusSeconds(60));

        stops.saveAndFlush(new TripStopEntity("acme", trip.getId(), stop));

        assertEquals(List.of(stop), stops.findByTripIdOrderByIdAsc(trip.getId()).stream()
                .map(TripStopEntity::toEngine).toList());
    }

    @Test
    void pingsComeBackInTimeOrderAndTheSameReadingIsStoredOnce() {
        TripEntity trip = newTrip("acme", "T-1");
        Ping later = new Ping(START.plusSeconds(20), new GeoPoint(12.92, 77.61), 5);
        Ping earlier = new Ping(START.plusSeconds(10), new GeoPoint(12.91, 77.61), 5);
        pings.saveAndFlush(new PingEntity("acme", trip.getId(), later, START));
        pings.saveAndFlush(new PingEntity("acme", trip.getId(), earlier, START));

        assertEquals(List.of(earlier, later), pings.findByTripIdOrderByRecordedAtAscIdAsc(trip.getId()).stream()
                .map(PingEntity::toEngine).toList());
        assertThrows(DataIntegrityViolationException.class,
                () -> pings.saveAndFlush(new PingEntity("acme", trip.getId(), earlier, START)));
    }

    @Test
    void tripRemembersItsLatestComputationAndState() {
        TripEntity trip = newTrip("acme", "T-1");
        DistanceComputationEntity first = newComputation(trip, TripStatus.DEGRADED);
        DistanceComputationEntity second = newComputation(trip, TripStatus.OK);

        trip.useComputation(second.getId(), DistanceState.OK);
        trips.saveAndFlush(trip);

        assertEquals(second.getId(), computations.findFirstByTripIdOrderByIdDesc(trip.getId()).orElseThrow().getId());
        assertEquals(2, computations.findByTripIdOrderByIdAsc(trip.getId()).size());
        assertEquals(second.getId(), trips.findById(trip.getId()).orElseThrow().getCurrentComputationId());
        assertNotNull(first.getSegmentsJson());
    }

    @Test
    void degradedTripsCanBeFoundOldestFirst() {
        TripEntity a = newTrip("acme", "T-1");
        TripEntity b = newTrip("acme", "T-2");
        TripEntity c = newTrip("acme", "T-3");
        a.useComputation(1L, DistanceState.DEGRADED);
        b.useComputation(2L, DistanceState.OK);
        c.useComputation(3L, DistanceState.DEGRADED);
        trips.saveAllAndFlush(List.of(a, b, c));

        List<TripEntity> degraded = trips.findTop100ByDistanceStateOrderByIdAsc(DistanceState.DEGRADED);

        assertEquals(List.of("T-1", "T-3"), degraded.stream().map(TripEntity::getTripRef).toList());
        assertEquals(2, trips.countByDistanceState(DistanceState.DEGRADED));
    }

    @Test
    void billingStaysClosedAndAdjustmentsAreSigned() {
        TripEntity trip = newTrip("acme", "T-1");
        DistanceComputationEntity billedRun = newComputation(trip, TripStatus.OK);
        trip.markBilled(new BigDecimal("30.60"), billedRun.getId());
        trips.saveAndFlush(trip);
        DistanceComputationEntity lateRun = newComputation(trip, TripStatus.OK);

        adjustments.saveAndFlush(new BillingAdjustmentEntity("acme", trip.getId(), lateRun.getId(),
                new BigDecimal("1.80"), "late pings", START));
        DistanceComputationEntity another = newComputation(trip, TripStatus.OK);
        adjustments.saveAndFlush(new BillingAdjustmentEntity("acme", trip.getId(), another.getId(),
                new BigDecimal("-0.40"), "late pings", START));

        assertTrue(trips.findById(trip.getId()).orElseThrow().isBilled());
        assertEquals(List.of(new BigDecimal("1.80"), new BigDecimal("-0.40")),
                adjustments.findByTripIdOrderByIdAsc(trip.getId()).stream()
                        .map(BillingAdjustmentEntity::getDeltaKm).toList());
        assertTrue(adjustments.existsByComputationId(lateRun.getId()));
    }

    @Test
    void oneComputationCanOnlyProduceOneAdjustment() {
        TripEntity trip = newTrip("acme", "T-1");
        DistanceComputationEntity run = newComputation(trip, TripStatus.OK);
        adjustments.saveAndFlush(new BillingAdjustmentEntity("acme", trip.getId(), run.getId(),
                new BigDecimal("1.80"), "late pings", START));

        assertThrows(DataIntegrityViolationException.class, () -> adjustments.saveAndFlush(
                new BillingAdjustmentEntity("acme", trip.getId(), run.getId(),
                        new BigDecimal("1.80"), "late pings again", START)));
    }
}

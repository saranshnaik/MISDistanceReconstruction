package com.moveinsync.distance.service;

import com.moveinsync.distance.engine.DistanceEngine;
import com.moveinsync.distance.engine.TripDistanceResult;
import com.moveinsync.distance.engine.TripStatus;
import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.Segment;
import com.moveinsync.distance.engine.model.TripEvidence;
import com.moveinsync.distance.persistence.BillingAdjustmentEntity;
import com.moveinsync.distance.persistence.BillingAdjustmentRepository;
import com.moveinsync.distance.persistence.DistanceComputationEntity;
import com.moveinsync.distance.persistence.DistanceComputationRepository;
import com.moveinsync.distance.persistence.DistanceState;
import com.moveinsync.distance.persistence.PingEntity;
import com.moveinsync.distance.persistence.PingRepository;
import com.moveinsync.distance.persistence.TripEntity;
import com.moveinsync.distance.persistence.TripRepository;
import com.moveinsync.distance.persistence.TripStopEntity;
import com.moveinsync.distance.persistence.TripStopRepository;
import java.time.Clock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores a trip's evidence and keeps its distance up to date.
 *
 * Two promises:
 *  - Ingesting the same trip ref again changes nothing (it is idempotent per tenant).
 *  - Computing twice on the same input and rules does not even run the engine twice: the
 *    last saved computation is returned. A DEGRADED computation never counts as "same",
 *    so asking again after the provider recovered gives the real answer.
 *
 * Every lookup carries the tenant id. A computation holds a row lock on the trip, so two
 * recomputes of one trip (even on two servers) happen one after the other.
 */
@Service
public class TripService {

    private final TripRepository trips;
    private final TripStopRepository stops;
    private final PingRepository pings;
    private final DistanceComputationRepository computations;
    private final BillingAdjustmentRepository adjustments;
    private final DistanceEngine engine;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public TripService(TripRepository trips, TripStopRepository stops, PingRepository pings,
                       DistanceComputationRepository computations,
                       BillingAdjustmentRepository adjustments, DistanceEngine engine,
                       JsonMapper json, Clock clock, PlatformTransactionManager transactionManager) {
        this.trips = trips;
        this.stops = stops;
        this.pings = pings;
        this.computations = computations;
        this.adjustments = adjustments;
        this.engine = engine;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Save the trip if it is new, then make sure it has an up-to-date distance. */
    public TripView ingest(String tenantId, String tripRef, TripEvidence evidence) {
        try {
            tx.executeWithoutResult(status -> createIfAbsent(tenantId, tripRef, evidence));
        } catch (DataIntegrityViolationException e) {
            // another request saved the same trip a moment earlier: that is fine, use theirs
        }
        return compute(tenantId, tripRef);
    }

    /** Run (or reuse) the computation for a trip. Also what "recompute" means. */
    public TripView compute(String tenantId, String tripRef) {
        return tx.execute(status -> {
            TripEntity trip = trips.findForUpdate(tenantId, tripRef).orElseThrow(TripNotFoundException::new);
            return computeLocked(trip, "recomputed");
        });
    }

    /**
     * The computation itself, for callers that already hold the trip's row lock.
     *
     * If the trip is already billed and this run gives a real (non-degraded) answer that differs
     * from what has been billed so far, the difference is stored as a signed adjustment. The
     * closed invoice itself is never edited.
     */
    TripView computeLocked(TripEntity trip, String reason) {
        TripEvidence evidence = loadEvidence(trip);
        String inputHash = InputHasher.hash(evidence);

        DistanceComputationEntity latest = computations.findFirstByTripIdOrderByIdDesc(trip.getId()).orElse(null);
        if (latest != null && isStillValid(latest, inputHash)) {
            return viewOf(trip, latest);
        }

        TripDistanceResult result = engine.compute(evidence);
        DistanceComputationEntity saved = computations.save(toEntity(trip, inputHash, result));
        trip.useComputation(saved.getId(),
                result.status() == TripStatus.OK ? DistanceState.OK : DistanceState.DEGRADED);
        trips.save(trip);
        if (trip.isBilled() && result.status() == TripStatus.OK) {
            adjustIfDifferent(trip, saved, reason);
        }
        return viewOf(trip, saved);
    }

    private void adjustIfDifferent(TripEntity trip, DistanceComputationEntity computation, String reason) {
        BigDecimal alreadyBilled = totalBilled(trip);
        BigDecimal delta = computation.getBillableKm().subtract(alreadyBilled);
        if (delta.signum() != 0) {
            adjustments.save(new BillingAdjustmentEntity(trip.getTenantId(), trip.getId(), computation.getId(),
                    delta, reason, clock.instant()));
        }
    }

    /** What the customer has been charged for so far: the closed invoice plus all adjustments. */
    private BigDecimal totalBilled(TripEntity trip) {
        BigDecimal total = trip.getBilledKm();
        for (BillingAdjustmentEntity adjustment : adjustments.findByTripIdOrderByIdAsc(trip.getId())) {
            total = total.add(adjustment.getDeltaKm());
        }
        return total;
    }

    TripView viewOf(TripEntity trip) {
        DistanceComputationEntity latest = computations.findFirstByTripIdOrderByIdDesc(trip.getId())
                .orElseThrow(TripNotFoundException::new);
        return viewOf(trip, latest);
    }

    private TripView viewOf(TripEntity trip, DistanceComputationEntity latest) {
        return TripView.of(trip, latest, trip.isBilled() ? totalBilled(trip) : null);
    }

    public TripView get(String tenantId, String tripRef) {
        return tx.execute(status -> {
            TripEntity trip = trips.findByTenantIdAndTripRef(tenantId, tripRef).orElseThrow(TripNotFoundException::new);
            return viewOf(trip);
        });
    }

    /**
     * A rider's view: only trips they were part of. Any other trip, including one that does not
     * exist, gives the same "not found", so a rider cannot probe for other people's trips.
     */
    public TripView getForEmployee(String tenantId, String tripRef, String employeeRef) {
        return tx.execute(status -> {
            TripEntity trip = trips.findByTenantIdAndTripRef(tenantId, tripRef).orElseThrow(TripNotFoundException::new);
            if (employeeRef == null || !stops.existsByTripIdAndEmployeeRef(trip.getId(), employeeRef)) {
                throw new TripNotFoundException();
            }
            return viewOf(trip);
        });
    }

    /** The line-by-line explanation of the latest computation. */
    public List<SegmentView> segments(String tenantId, String tripRef) {
        String stored = tx.execute(status -> {
            TripEntity trip = trips.findByTenantIdAndTripRef(tenantId, tripRef).orElseThrow(TripNotFoundException::new);
            return computations.findFirstByTripIdOrderByIdDesc(trip.getId())
                    .orElseThrow(TripNotFoundException::new).getSegmentsJson();
        });
        return json.readValue(stored, new TypeReference<List<SegmentView>>() { });
    }

    private boolean isStillValid(DistanceComputationEntity latest, String inputHash) {
        return latest.getInputHash().equals(inputHash)
                && latest.getRuleVersion().equals(engine.ruleVersion())
                && latest.getStatus() == TripStatus.OK;
    }

    private void createIfAbsent(String tenantId, String tripRef, TripEvidence evidence) {
        if (trips.findByTenantIdAndTripRef(tenantId, tripRef).isPresent()) {
            return;
        }
        TripEntity trip = trips.saveAndFlush(new TripEntity(tenantId, tripRef, evidence.type(),
                evidence.tripStart(), evidence.tripEnd(), evidence.actualStartPoint(), evidence.actualEndPoint(),
                evidence.office(), clock.instant()));

        List<TripStopEntity> stopRows = new ArrayList<>();
        for (EmployeeStop stop : evidence.stops()) {
            stopRows.add(new TripStopEntity(tenantId, trip.getId(), stop));
        }
        stops.saveAll(stopRows);

        // the same reading sent twice in one payload is stored once
        Set<PingKey> seen = new HashSet<>();
        List<PingEntity> pingRows = new ArrayList<>();
        for (Ping ping : evidence.pings()) {
            if (seen.add(new PingKey(ping))) {
                pingRows.add(new PingEntity(tenantId, trip.getId(), ping, clock.instant()));
            }
        }
        pings.saveAll(pingRows);
    }

    private TripEvidence loadEvidence(TripEntity trip) {
        List<Ping> storedPings = pings.findByTripIdOrderByRecordedAtAscIdAsc(trip.getId())
                .stream().map(PingEntity::toEngine).toList();
        List<EmployeeStop> storedStops = stops.findByTripIdOrderByIdAsc(trip.getId())
                .stream().map(TripStopEntity::toEngine).toList();
        return new TripEvidence(trip.getTripType(), trip.getTripStart(), trip.getTripEnd(),
                trip.actualStartPoint(), trip.actualEndPoint(), trip.officePoint(), storedPings, storedStops);
    }

    private DistanceComputationEntity toEntity(TripEntity trip, String inputHash, TripDistanceResult result) {
        List<SegmentView> views = new ArrayList<>(result.segments().size());
        int index = 0;
        for (Segment segment : result.segments()) {
            views.add(SegmentView.of(index++, segment));
        }
        return new DistanceComputationEntity(trip.getTenantId(), trip.getId(), inputHash, result.ruleVersion(),
                result.totalMm(), result.billableKm(), result.naiveKm(), result.status(),
                result.routing().routeRequests(), result.routing().providerCalls(), result.routing().cacheHits(),
                json.writeValueAsString(views), clock.instant());
    }

    /** What makes two readings "the same" for storage: when, and where. */
    private record PingKey(Instant time, double lat, double lon) {
        PingKey(Ping ping) {
            this(ping.time(), ping.point().lat(), ping.point().lon());
        }
    }
}

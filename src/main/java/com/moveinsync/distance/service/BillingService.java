package com.moveinsync.distance.service;

import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.observability.EngineMetrics;
import com.moveinsync.distance.persistence.BillingAdjustmentRepository;
import com.moveinsync.distance.persistence.DistanceComputationEntity;
import com.moveinsync.distance.persistence.DistanceComputationRepository;
import com.moveinsync.distance.persistence.DistanceState;
import com.moveinsync.distance.persistence.PingEntity;
import com.moveinsync.distance.persistence.PingRepository;
import com.moveinsync.distance.persistence.TripEntity;
import com.moveinsync.distance.persistence.TripRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closing invoices and dealing with evidence that arrives afterwards. A closed invoice is
 * never edited: if the distance changes later (late pings, or a degraded estimate replaced by
 * the real answer) TripService stores a signed adjustment. This class just feeds in the new
 * evidence under the trip's row lock.
 */
@Service
public class BillingService {

    private final TripRepository trips;
    private final PingRepository pings;
    private final DistanceComputationRepository computations;
    private final BillingAdjustmentRepository adjustments;
    private final TripService tripService;
    private final Clock clock;
    private final EngineMetrics metrics;
    private final TransactionTemplate tx;

    public BillingService(TripRepository trips, PingRepository pings, DistanceComputationRepository computations,
                          BillingAdjustmentRepository adjustments, TripService tripService, Clock clock,
                          EngineMetrics metrics, PlatformTransactionManager transactionManager) {
        this.trips = trips;
        this.pings = pings;
        this.computations = computations;
        this.adjustments = adjustments;
        this.tripService = tripService;
        this.clock = clock;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Close the invoice at the trip's current distance. Billing twice does nothing the second
     * time. Never-computed or DEGRADED trips can't be billed.
     */
    public TripView bill(String tenantId, String tripRef) {
        return tx.execute(status -> {
            TripEntity trip = trips.findForUpdate(tenantId, tripRef).orElseThrow(TripNotFoundException::new);
            if (trip.isBilled()) {
                return tripService.viewOf(trip);
            }
            DistanceComputationEntity latest = computations.findFirstByTripIdOrderByIdDesc(trip.getId())
                    .orElseThrow(TripNotBillableException::new);
            if (trip.getDistanceState() != DistanceState.OK) {
                throw new TripNotBillableException();
            }
            trip.markBilled(latest.getBillableKm(), latest.getId());
            trips.save(trip);
            metrics.invoiceClosed();
            return tripService.viewOf(trip);
        });
    }

    /**
     * Add pings that arrived late and recompute. Readings we already have are ignored, so the
     * same batch twice changes nothing. On a billed trip the change comes out as a signed
     * adjustment.
     */
    public TripView addLatePings(String tenantId, String tripRef, List<Ping> latePings) {
        return tx.execute(status -> {
            TripEntity trip = trips.findForUpdate(tenantId, tripRef).orElseThrow(TripNotFoundException::new);

            Set<PingKey> known = new HashSet<>();
            for (PingEntity existing : pings.findByTripIdOrderByRecordedAtAscIdAsc(trip.getId())) {
                known.add(new PingKey(existing.toEngine()));
            }
            Instant now = clock.instant();
            for (Ping ping : latePings) {
                if (known.add(new PingKey(ping))) {
                    pings.save(new PingEntity(tenantId, trip.getId(), ping, now));
                    metrics.latePing(Duration.between(ping.time(), now).toSeconds());
                }
            }
            pings.flush();
            return tripService.computeLocked(trip, "late pings changed the distance");
        });
    }

    public BillingSummary summary(String tenantId, String tripRef) {
        return tx.execute(status -> {
            TripEntity trip = trips.findByTenantIdAndTripRef(tenantId, tripRef).orElseThrow(TripNotFoundException::new);
            List<AdjustmentView> views = adjustments.findByTripIdOrderByIdAsc(trip.getId())
                    .stream().map(AdjustmentView::of).toList();
            BigDecimal finalKm = trip.isBilled() ? trip.getBilledKm() : null;
            for (AdjustmentView view : views) {
                finalKm = finalKm.add(view.deltaKm());
            }
            return new BillingSummary(trip.getBilledKm(), finalKm, views);
        });
    }

    private record PingKey(Instant time, double lat, double lon) {
        PingKey(Ping ping) {
            this(ping.time(), ping.point().lat(), ping.point().lon());
        }
    }
}

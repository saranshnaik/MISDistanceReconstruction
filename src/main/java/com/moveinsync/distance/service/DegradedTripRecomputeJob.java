package com.moveinsync.distance.service;

import com.moveinsync.distance.engine.routing.CircuitBreaker;
import com.moveinsync.distance.observability.EngineMetrics;
import com.moveinsync.distance.persistence.DistanceState;
import com.moveinsync.distance.persistence.TripEntity;
import com.moveinsync.distance.persistence.TripRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Heals trips that were only estimated because routing was down.
 *
 * Every few seconds it looks for DEGRADED trips (oldest first) and recomputes them. Three
 * things keep it from making a bad day worse:
 *  - while the circuit breaker is still refusing requests it does nothing at all
 *  - the first trip that comes back DEGRADED again means the provider is still unwell, so it
 *    stops there instead of burning through the whole list
 *  - it works on 100 trips per run, so a long outage drains in steady steps afterwards
 *
 * Several servers can run this at once: TripService recomputes under the trip's row lock, so
 * the second one just finds the work already done.
 */
@Component
public class DegradedTripRecomputeJob {

    private static final Logger log = LoggerFactory.getLogger(DegradedTripRecomputeJob.class);

    private final TripRepository trips;
    private final TripService tripService;
    private final CircuitBreaker breaker;
    private final EngineMetrics metrics;

    public DegradedTripRecomputeJob(TripRepository trips, TripService tripService, CircuitBreaker breaker,
                                    EngineMetrics metrics) {
        this.trips = trips;
        this.tripService = tripService;
        this.breaker = breaker;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${recompute.interval-ms:30000}")
    public void scheduledRun() {
        runOnce();
    }

    /** One pass. Returns how many trips were brought back to OK. */
    public int runOnce() {
        if (breaker.isRefusingRequests()) {
            log.debug("recompute skipped: routing circuit is open");
            metrics.recompute("skipped_circuit_open", 1);
            return 0;
        }
        List<TripEntity> degraded = trips.findTop100ByDistanceStateOrderByIdAsc(DistanceState.DEGRADED);
        int recovered = 0;
        for (TripEntity trip : degraded) {
            try {
                TripView view = tripService.compute(trip.getTenantId(), trip.getTripRef());
                if (view.distanceState() != DistanceState.OK) {
                    metrics.recompute("still_degraded", 1);
                    log.info("recompute stopped: routing still unavailable, recovered={} waiting={}",
                            recovered, degraded.size() - recovered);
                    return recovered;
                }
                recovered++;
                metrics.recompute("recovered", 1);
            } catch (RuntimeException e) {
                // one bad trip must not block the rest; keep the log free of trip data
                metrics.recompute("failed", 1);
                log.warn("recompute failed for one trip: {}", e.getClass().getSimpleName());
            }
        }
        if (!degraded.isEmpty()) {
            log.info("recompute finished: recovered={}", recovered);
        }
        return recovered;
    }
}

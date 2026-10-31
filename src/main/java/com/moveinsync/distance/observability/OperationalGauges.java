package com.moveinsync.distance.observability;

import com.moveinsync.distance.engine.routing.CachingRouteResolver;
import com.moveinsync.distance.engine.routing.CircuitBreaker;
import com.moveinsync.distance.persistence.DistanceState;
import com.moveinsync.distance.persistence.TripRepository;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Numbers read off the current state whenever Prometheus scrapes, instead of counted as
 * things happen: backlog size, its age, and what the circuit breaker is doing.
 */
@Component
public class OperationalGauges {

    public OperationalGauges(MeterRegistry registry, TripRepository trips, CircuitBreaker breaker,
                             CachingRouteResolver cache, Clock clock) {
        Gauge.builder("distance.trips.degraded", () -> trips.countByDistanceState(DistanceState.DEGRADED))
                .description("queue depth: trips only estimated so far, waiting to be recomputed")
                .register(registry);

        Gauge.builder("distance.trips.degraded.oldest.age.seconds", () -> trips
                        .findFirstByDistanceStateOrderByIdAsc(DistanceState.DEGRADED)
                        .map(trip -> (double) Duration.between(trip.getCreatedAt(), clock.instant()).toSeconds())
                        .orElse(0.0))
                .baseUnit("seconds")
                .description("lag: how long the oldest estimated trip has been waiting (0 if none)")
                .register(registry);

        Gauge.builder("distance.routing.circuit.state", () -> breaker.state().ordinal())
                .description("routing circuit breaker: 0 closed, 1 half-open (testing), 2 open")
                .register(registry);

        FunctionCounter.builder("distance.routing.circuit.opened", breaker, CircuitBreaker::timesOpened)
                .description("how many times the routing circuit has tripped").register(registry);

        Gauge.builder("distance.routing.cache.size", cache::size)
                .description("distinct routes currently cached").register(registry);
    }
}

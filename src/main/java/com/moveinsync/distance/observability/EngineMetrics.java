package com.moveinsync.distance.observability;

import com.moveinsync.distance.engine.TripDistanceResult;
import com.moveinsync.distance.engine.TripStatus;
import com.moveinsync.distance.engine.cleaning.CleaningReport;
import com.moveinsync.distance.engine.model.Segment;
import com.moveinsync.distance.engine.model.SegmentRule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Every number an on-call engineer would reach for at 3 AM, recorded in one place.
 *
 * Tags only ever come from fixed lists (rules, drop reasons, outcomes). Never a trip ref, a
 * tenant, an employee or a coordinate: metric labels are stored forever and shown to everyone
 * with dashboard access.
 *
 * What answers which question:
 *   "is it slow?"            distance.compute (timer, by status)
 *   "is the data bad?"       distance.pings.dropped by reason, distance.segments by rule (REJECTED_*)
 *   "is routing hurting?"    distance.routing.* (requests, calls, hits, failures, circuit state)
 *   "are we falling behind?" distance.trips.degraded (queue depth), ...oldest.age.seconds (lag)
 *   "are retries working?"   distance.recompute by outcome
 *   "is money moving?"       distance.billing.*
 *   "are requests failing?"  http.server.requests (Spring's own, by status)
 */
@Component
public class EngineMetrics {

    private final MeterRegistry registry;

    public EngineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One finished engine run. */
    public void computation(TripDistanceResult result, long nanos) {
        Timer.builder("distance.compute").description("time to rebuild one trip's distance")
                .tag("status", result.status().name()).register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);

        CleaningReport cleaning = result.cleaning();
        add("distance.pings.received", "all pings sent for a trip", cleaning.received());
        dropped("outside_window", cleaning.outsideTripWindow());
        dropped("invalid_coordinates", cleaning.invalidCoordinates());
        dropped("low_accuracy", cleaning.lowAccuracy());
        dropped("duplicate", cleaning.duplicates());
        dropped("teleport", cleaning.teleports());
        add("distance.anchors.skipped", "milestones with no usable location or time", result.skippedAnchors());

        // counted locally first: a trip can have a thousand segments
        Map<SegmentRule, Integer> perRule = new EnumMap<>(SegmentRule.class);
        for (Segment segment : result.segments()) {
            perRule.merge(segment.rule(), 1, Integer::sum);
        }
        perRule.forEach((rule, count) -> Counter.builder("distance.segments")
                .description("segments by the rule that priced them").tag("rule", rule.name())
                .register(registry).increment(count));

        add("distance.routing.requests", "gaps that needed a driving distance", result.routing().routeRequests());
        add("distance.routing.calls", "requests that really went to the routing provider", result.routing().providerCalls());
        add("distance.routing.cache.hits", "routes served from the cache", result.routing().cacheHits());
        if (result.status() == TripStatus.DEGRADED) {
            add("distance.routing.failures", "trips estimated because routing was unavailable", 1);
        }
    }

    /** A signed adjustment was added to a closed invoice. */
    public void adjustment(BigDecimal deltaKm) {
        Counter.builder("distance.billing.adjustments").description("signed corrections to closed invoices")
                .tag("direction", deltaKm.signum() > 0 ? "positive" : "negative")
                .register(registry).increment();
    }

    public void invoiceClosed() {
        add("distance.billing.closed", "invoices closed", 1);
    }

    /** A ping we had not seen before arrived after the trip was already stored. */
    public void latePing(long lagSeconds) {
        add("distance.pings.late", "pings accepted after the trip was stored", 1);
        DistributionSummary.builder("distance.ping.lag.seconds").baseUnit("seconds")
                .description("how long after it was recorded a late ping reached us")
                .register(registry).record(Math.max(0, lagSeconds));
    }

    /** What one pass of the recovery job did. */
    public void recompute(String outcome, int trips) {
        if (trips > 0) {
            Counter.builder("distance.recompute").description("recovery job outcomes, per trip")
                    .tag("outcome", outcome).register(registry).increment(trips);
        }
    }

    private void dropped(String reason, int count) {
        if (count > 0) {
            Counter.builder("distance.pings.dropped").description("pings thrown away by the cleaner")
                    .tag("reason", reason).register(registry).increment(count);
        }
    }

    private void add(String name, String description, int amount) {
        Counter.builder(name).description(description).register(registry).increment(amount);
    }
}

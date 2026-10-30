package com.moveinsync.distance.engine.rules;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.geo.Millimetres;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.Segment;
import com.moveinsync.distance.engine.model.SegmentRule;
import com.moveinsync.distance.engine.model.TrackPoint;
import java.time.Duration;
import java.util.Optional;

/**
 * Base for the rules that bill the straight-line distance. The measuring and the Segment
 * building are the same every time; subclasses only decide WHEN they apply and what to call it.
 */
public abstract class AerialSegmentRule implements SegmentDistanceRule {

    protected final RuleConfig config;

    protected AerialSegmentRule(RuleConfig config) {
        this.config = config;
    }

    @Override
    public final Optional<Segment> apply(TrackPoint from, TrackPoint to) {
        long durationMs = Duration.between(from.time(), to.time()).toMillis();
        double meters = Haversine.distanceM(from.point(), to.point());

        if (!appliesTo(durationMs, Speed.kmh(meters, durationMs))) {
            return Optional.empty();
        }
        long mm = Millimetres.fromMeters(meters);
        return Optional.of(new Segment(from, to, durationMs, mm, null, mm, label()));
    }

    protected abstract boolean appliesTo(long durationMs, double speedKmh);

    protected abstract SegmentRule label();

    protected boolean isShortGap(long durationMs) {
        return durationMs < config.shortGapSeconds() * 1000;
    }
}

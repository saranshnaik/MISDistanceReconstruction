package com.moveinsync.distance.engine.rules;

import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.SegmentRule;

/**
 * Gap of 70 s or more where the cab moved slower than 5 km/h: parked or crawling, so the
 * straight line is the honest answer and routing would only invent a detour.
 */
public class ParkedRule extends AerialSegmentRule {

    public ParkedRule(RuleConfig config) {
        super(config);
    }

    @Override
    protected boolean appliesTo(long durationMs, double speedKmh) {
        return !isShortGap(durationMs) && speedKmh < config.parkedSpeedKmh();
    }

    @Override
    protected SegmentRule label() {
        return SegmentRule.PARKED;
    }
}

package com.moveinsync.distance.engine.rules;

import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.SegmentRule;

/**
 * Two neighbouring points less than 70 seconds apart, at a speed the cab could really do:
 * the straight line is accurate enough and not worth a routing call.
 */
public class ShortGapAerialRule extends AerialSegmentRule {

    public ShortGapAerialRule(RuleConfig config) {
        super(config);
    }

    @Override
    protected boolean appliesTo(long durationMs, double speedKmh) {
        return isShortGap(durationMs) && speedKmh <= config.maxSpeedKmh();
    }

    @Override
    protected SegmentRule label() {
        return SegmentRule.SHORT_GAP_AERIAL;
    }
}

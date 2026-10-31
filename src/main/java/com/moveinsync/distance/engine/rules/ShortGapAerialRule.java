package com.moveinsync.distance.engine.rules;

import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.SegmentRule;

/**
 * Under 70 s between two points, at a speed the cab could really do: the straight line is
 * close enough and not worth a routing call.
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

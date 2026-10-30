package com.moveinsync.distance.engine.rules;

import com.moveinsync.distance.engine.model.Segment;
import com.moveinsync.distance.engine.model.TrackPoint;
import java.util.Optional;

/**
 * A rule that can price a segment on its own, without calling anyone.
 * Returns empty when the rule does not apply, so the next rule gets a go.
 */
public interface SegmentDistanceRule {

    Optional<Segment> apply(TrackPoint from, TrackPoint to);
}

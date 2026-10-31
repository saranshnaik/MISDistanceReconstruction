package com.moveinsync.distance.engine.anchors;

import com.moveinsync.distance.engine.model.TrackPoint;
import java.util.List;

/** Anchors in time order, plus how many milestones had no usable location or time. */
public record AnchorResult(List<TrackPoint> anchors, int skipped) {
}

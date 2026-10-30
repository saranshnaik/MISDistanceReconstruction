package com.moveinsync.distance.engine.anchors;

import com.moveinsync.distance.engine.model.TrackPoint;
import java.util.List;

/**
 * The anchors we managed to build, oldest first, and how many milestones had no usable
 * location/time at all (so they could not be anchored).
 */
public record AnchorResult(List<TrackPoint> anchors, int skipped) {
}

package com.moveinsync.distance.service;

import com.moveinsync.distance.engine.model.PointSource;
import com.moveinsync.distance.engine.model.Segment;
import com.moveinsync.distance.engine.model.TrackPoint;
import java.time.Instant;

/**
 * One line of the explanation a vendor can read: which two points, how far, which rule.
 * A rider's home address is personal data, so points that came from a home location carry no
 * coordinates here (the source still says where they came from).
 */
public record SegmentView(
        int index,
        String rule,
        String fromSource,
        String fromRef,
        Instant fromTime,
        Double fromLat,
        Double fromLon,
        String toSource,
        String toRef,
        Instant toTime,
        Double toLat,
        Double toLon,
        long durationMs,
        long aerialMm,
        Long routedMm,
        long billedMm) {

    public static SegmentView of(int index, Segment segment) {
        TrackPoint from = segment.from();
        TrackPoint to = segment.to();
        return new SegmentView(index, segment.rule().name(),
                from.source().name(), from.ref(), from.time(), lat(from), lon(from),
                to.source().name(), to.ref(), to.time(), lat(to), lon(to),
                segment.durationMs(), segment.aerialMm(), segment.routedMm(), segment.billedMm());
    }

    private static Double lat(TrackPoint point) {
        return point.source() == PointSource.EMPLOYEE_HOME_SIGNIN ? null : point.point().lat();
    }

    private static Double lon(TrackPoint point) {
        return point.source() == PointSource.EMPLOYEE_HOME_SIGNIN ? null : point.point().lon();
    }
}

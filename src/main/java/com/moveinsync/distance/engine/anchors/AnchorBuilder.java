package com.moveinsync.distance.engine.anchors;

import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.PointSource;
import com.moveinsync.distance.engine.model.RiderType;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.StopEvent;
import com.moveinsync.distance.engine.model.TrackPoint;
import com.moveinsync.distance.engine.model.TripEvidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Builds the anchor points that plug the holes in the GPS track: the places where we know the
 * cab must have been (employee pickups/drops, no-shows, the office), even if no ping says so.
 *
 * A candidate (point + time) is only usable when the point is a real coordinate and the time
 * falls inside the trip window. If it is not usable we try the next fallback.
 */
public class AnchorBuilder {

    /** Fixed order so two anchors at the same instant always end up in the same sequence. */
    private static final Comparator<TrackPoint> ANCHOR_ORDER =
            Comparator.comparing(TrackPoint::time)
                    .thenComparing(TrackPoint::source)
                    .thenComparing(TrackPoint::ref, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparingDouble(p -> p.point().lat())
                    .thenComparingDouble(p -> p.point().lon());

    private final RuleConfig config;

    public AnchorBuilder(RuleConfig config) {
        this.config = config;
    }

    public AnchorResult build(TripEvidence trip) {
        List<TrackPoint> anchors = new ArrayList<>();
        int skipped = 0;

        for (EmployeeStop stop : trip.stops()) {
            if (stop.event() == StopEvent.NO_SHOW && !config.includeNoShows()) {
                continue; // flag is off: the cab never really went there, so nothing to add
            }
            Optional<TrackPoint> anchor = stop.event() == StopEvent.NO_SHOW
                    ? noShowAnchor(stop, trip)
                    : employeeAnchor(stop, trip);
            if (anchor.isPresent()) {
                anchors.add(anchor.get());
            } else {
                skipped++;
            }
        }

        anchors.sort(ANCHOR_ORDER);
        return new AnchorResult(anchors, skipped);
    }

    /**
     * Fallback order for someone who actually travelled:
     *  1. the pickup/drop the driver recorded
     *  2. ad-hoc rider: profile home location at sign-in time
     *     planned rider: planned location at planned time
     */
    private Optional<TrackPoint> employeeAnchor(EmployeeStop stop, TripEvidence trip) {
        if (isUsable(stop.actualPoint(), stop.actualTime(), trip)) {
            return Optional.of(new TrackPoint(
                    stop.actualTime(), stop.actualPoint(), PointSource.EMPLOYEE_ACTUAL, stop.employeeRef()));
        }
        if (stop.riderType() == RiderType.AD_HOC) {
            if (isUsable(stop.homePoint(), stop.signInTime(), trip)) {
                return Optional.of(new TrackPoint(
                        stop.signInTime(), stop.homePoint(), PointSource.EMPLOYEE_HOME_SIGNIN, stop.employeeRef()));
            }
        } else if (isUsable(stop.plannedPoint(), stop.plannedTime(), trip)) {
            return Optional.of(new TrackPoint(
                    stop.plannedTime(), stop.plannedPoint(), PointSource.EMPLOYEE_PLANNED, stop.employeeRef()));
        }
        return Optional.empty();
    }

    /**
     * A no-show means the cab went to the pickup spot and the employee never turned up.
     * We use the spot where the driver marked the no-show, or the planned spot if that
     * is not usable. Only called when the includeNoShows flag is on.
     */
    private Optional<TrackPoint> noShowAnchor(EmployeeStop stop, TripEvidence trip) {
        if (isUsable(stop.actualPoint(), stop.actualTime(), trip)) {
            return Optional.of(new TrackPoint(
                    stop.actualTime(), stop.actualPoint(), PointSource.NO_SHOW_ACTUAL, stop.employeeRef()));
        }
        if (isUsable(stop.plannedPoint(), stop.plannedTime(), trip)) {
            return Optional.of(new TrackPoint(
                    stop.plannedTime(), stop.plannedPoint(), PointSource.NO_SHOW_PLANNED, stop.employeeRef()));
        }
        return Optional.empty();
    }

    private boolean isUsable(GeoPoint point, Instant time, TripEvidence trip) {
        return point != null
                && time != null
                && point.isValid()
                && !time.isBefore(trip.tripStart())
                && !time.isAfter(trip.tripEnd());
    }
}

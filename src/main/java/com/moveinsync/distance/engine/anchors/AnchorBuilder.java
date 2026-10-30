package com.moveinsync.distance.engine.anchors;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.PointSource;
import com.moveinsync.distance.engine.model.RiderType;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.StopEvent;
import com.moveinsync.distance.engine.model.TrackPoint;
import com.moveinsync.distance.engine.model.TripEvidence;
import com.moveinsync.distance.engine.model.TripType;
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

    private static final String OFFICE_REF = "OFFICE";

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

        anchors.add(officeAnchor(trip));

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

    /**
     * The office is where a LOGIN trip ends and where a LOGOUT trip starts.
     * We trust the device's own start/end point only if it is within officeRadiusM (400 m) of
     * the planned office. If it is missing, invalid or further away (cab stopped at the gate
     * road, bad fix...) we use the planned office location at the same trip start/end time.
     */
    private TrackPoint officeAnchor(TripEvidence trip) {
        boolean tripEndsAtOffice = trip.type() == TripType.LOGIN;
        Instant time = tripEndsAtOffice ? trip.tripEnd() : trip.tripStart();
        GeoPoint actual = tripEndsAtOffice ? trip.actualEndPoint() : trip.actualStartPoint();

        if (actual != null && actual.isValid()
                && Haversine.distanceM(actual, trip.office()) <= config.officeRadiusM()) {
            return new TrackPoint(time, actual, PointSource.OFFICE_ACTUAL, OFFICE_REF);
        }
        return new TrackPoint(time, trip.office(), PointSource.OFFICE_PLANNED, OFFICE_REF);
    }

    private boolean isUsable(GeoPoint point, Instant time, TripEvidence trip) {
        return point != null
                && time != null
                && point.isValid()
                && !time.isBefore(trip.tripStart())
                && !time.isAfter(trip.tripEnd());
    }
}

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
 * Builds the extra points that cover the holes in the GPS track: where the cab must have been
 * (pickups, drops, no-shows, the office) even when no ping says so.
 * A point only counts if it is a real coordinate and its time is inside the trip window,
 * otherwise we fall through to the next option.
 */
public class AnchorBuilder {

    /** Fixed order, so two anchors at the same instant always land in the same sequence. */
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
                continue; // flag off: nothing to add for a no-show
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
     * Someone who actually rode: the driver's pickup/drop first, then home + sign-in time for
     * ad-hoc riders or planned location + planned time for roster riders.
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
     * Cab went to the spot and the employee never showed. Use where the driver marked it,
     * or the planned spot if that is unusable. Only called when includeNoShows is on.
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
     * The office is where a LOGIN trip ends and a LOGOUT trip starts. The device's own point is
     * trusted only within officeRadiusM (400 m) of the office; otherwise (missing, invalid, cab
     * stopped down the road) we use the office itself at the trip start/end time.
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

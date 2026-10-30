package com.moveinsync.distance.engine.anchors;

import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.RiderType;
import com.moveinsync.distance.engine.model.StopEvent;
import com.moveinsync.distance.engine.model.TrackPoint;
import com.moveinsync.distance.engine.model.TripEvidence;
import com.moveinsync.distance.engine.model.TripType;
import java.time.Instant;
import java.util.List;

/** Small helpers so the anchor tests read like a story instead of a wall of constructors. */
final class TripFixtures {

    static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    static final Instant END = Instant.parse("2026-03-01T09:00:00Z");
    static final GeoPoint OFFICE = new GeoPoint(12.9352, 77.6245);

    static final GeoPoint HOME_A = new GeoPoint(12.9100, 77.5900);
    static final GeoPoint PLANNED_A = new GeoPoint(12.9110, 77.5910);
    static final GeoPoint ACTUAL_A = new GeoPoint(12.9105, 77.5905);

    private TripFixtures() {
    }

    /** The office anchor is always present; the employee tests only care about the others. */
    static List<TrackPoint> employeeAnchors(AnchorResult result) {
        return result.anchors().stream().filter(a -> !"OFFICE".equals(a.ref())).toList();
    }

    static Instant at(int minutesAfterStart) {
        return START.plusSeconds(minutesAfterStart * 60L);
    }

    static TripEvidence trip(TripType type, EmployeeStop... stops) {
        return new TripEvidence(type, START, END, null, null, OFFICE, List.of(), List.of(stops));
    }

    static EmployeeStop stop(String ref, RiderType rider, StopEvent event,
                             GeoPoint actualPoint, Instant actualTime,
                             GeoPoint plannedPoint, Instant plannedTime,
                             GeoPoint homePoint, Instant signInTime) {
        return new EmployeeStop(ref, rider, event, actualPoint, actualTime,
                plannedPoint, plannedTime, homePoint, signInTime);
    }
}

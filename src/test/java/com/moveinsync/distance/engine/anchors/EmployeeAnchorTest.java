package com.moveinsync.distance.engine.anchors;

import static com.moveinsync.distance.engine.anchors.TripFixtures.ACTUAL_A;
import static com.moveinsync.distance.engine.anchors.TripFixtures.HOME_A;
import static com.moveinsync.distance.engine.anchors.TripFixtures.PLANNED_A;
import static com.moveinsync.distance.engine.anchors.TripFixtures.at;
import static com.moveinsync.distance.engine.anchors.TripFixtures.stop;
import static com.moveinsync.distance.engine.anchors.TripFixtures.trip;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.PointSource;
import com.moveinsync.distance.engine.model.RiderType;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.StopEvent;
import com.moveinsync.distance.engine.model.TrackPoint;
import com.moveinsync.distance.engine.model.TripType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmployeeAnchorTest {

    private final AnchorBuilder builder = new AnchorBuilder(RuleConfig.defaults());

    private AnchorResult build(EmployeeStop... stops) {
        return builder.build(trip(TripType.LOGOUT, stops));
    }

    @Test
    void validActualPointIsUsedFirst() {
        AnchorResult result = build(stop("E1", RiderType.PLANNED, StopEvent.DROP,
                ACTUAL_A, at(20), PLANNED_A, at(25), null, null));

        TrackPoint anchor = result.anchors().get(0);
        assertEquals(PointSource.EMPLOYEE_ACTUAL, anchor.source());
        assertEquals(ACTUAL_A, anchor.point());
        assertEquals(at(20), anchor.time());
        assertEquals("E1", anchor.ref());
    }

    @Test
    void adHocRiderFallsBackToHomeAtSignInTime() {
        AnchorResult result = build(stop("E1", RiderType.AD_HOC, StopEvent.DROP,
                null, null, null, null, HOME_A, at(10)));

        TrackPoint anchor = result.anchors().get(0);
        assertEquals(PointSource.EMPLOYEE_HOME_SIGNIN, anchor.source());
        assertEquals(HOME_A, anchor.point());
        assertEquals(at(10), anchor.time());
    }

    @Test
    void plannedRiderFallsBackToPlannedLocationAndTime() {
        AnchorResult result = build(stop("E1", RiderType.PLANNED, StopEvent.PICKUP,
                null, null, PLANNED_A, at(5), null, null));

        TrackPoint anchor = result.anchors().get(0);
        assertEquals(PointSource.EMPLOYEE_PLANNED, anchor.source());
        assertEquals(PLANNED_A, anchor.point());
        assertEquals(at(5), anchor.time());
    }

    @Test
    void invalidActualCoordinatesTriggerTheFallback() {
        AnchorResult result = build(stop("E1", RiderType.PLANNED, StopEvent.DROP,
                new GeoPoint(0, 0), at(20), PLANNED_A, at(25), null, null));

        assertEquals(PointSource.EMPLOYEE_PLANNED, result.anchors().get(0).source());
    }

    @Test
    void actualTimeOutsideTheTripWindowTriggersTheFallback() {
        Instant afterTheTrip = Instant.parse("2026-03-01T09:30:00Z");
        AnchorResult result = build(stop("E1", RiderType.PLANNED, StopEvent.DROP,
                ACTUAL_A, afterTheTrip, PLANNED_A, at(25), null, null));

        assertEquals(PointSource.EMPLOYEE_PLANNED, result.anchors().get(0).source());
    }

    @Test
    void missingActualTimeTriggersTheFallback() {
        AnchorResult result = build(stop("E1", RiderType.AD_HOC, StopEvent.PICKUP,
                ACTUAL_A, null, null, null, HOME_A, at(10)));

        assertEquals(PointSource.EMPLOYEE_HOME_SIGNIN, result.anchors().get(0).source());
    }

    @Test
    void adHocRiderDoesNotUseThePlannedFields() {
        // an ad-hoc rider has no roster entry, so planned data must not be picked up
        AnchorResult result = build(stop("E1", RiderType.AD_HOC, StopEvent.DROP,
                null, null, PLANNED_A, at(25), null, null));

        assertEquals(0, result.anchors().size());
        assertEquals(1, result.skipped());
    }

    @Test
    void riderWithNothingUsableIsSkippedAndCounted() {
        AnchorResult result = build(stop("E1", RiderType.PLANNED, StopEvent.DROP,
                null, null, null, null, null, null));

        assertEquals(0, result.anchors().size());
        assertEquals(1, result.skipped());
    }

    @Test
    void anchorsComeBackInTimeOrder() {
        AnchorResult result = build(
                stop("E2", RiderType.PLANNED, StopEvent.DROP, ACTUAL_A, at(40), null, null, null, null),
                stop("E1", RiderType.PLANNED, StopEvent.DROP, ACTUAL_A, at(15), null, null, null, null),
                stop("E3", RiderType.PLANNED, StopEvent.DROP, ACTUAL_A, at(25), null, null, null, null));

        assertEquals(List.of("E1", "E3", "E2"),
                result.anchors().stream().map(TrackPoint::ref).toList());
    }

    @Test
    void sameInputInAnyOrderGivesTheSameAnchors() {
        EmployeeStop e1 = stop("E1", RiderType.PLANNED, StopEvent.DROP, ACTUAL_A, at(15), null, null, null, null);
        EmployeeStop e2 = stop("E2", RiderType.PLANNED, StopEvent.DROP, ACTUAL_A, at(15), null, null, null, null);

        assertEquals(build(e1, e2).anchors(), build(e2, e1).anchors());
    }
}

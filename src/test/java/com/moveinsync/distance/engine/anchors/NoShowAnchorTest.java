package com.moveinsync.distance.engine.anchors;

import static com.moveinsync.distance.engine.anchors.TripFixtures.ACTUAL_A;
import static com.moveinsync.distance.engine.anchors.TripFixtures.PLANNED_A;
import static com.moveinsync.distance.engine.anchors.TripFixtures.at;
import static com.moveinsync.distance.engine.anchors.TripFixtures.employeeAnchors;
import static com.moveinsync.distance.engine.anchors.TripFixtures.stop;
import static com.moveinsync.distance.engine.anchors.TripFixtures.trip;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.PointSource;
import com.moveinsync.distance.engine.model.RiderType;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.StopEvent;
import com.moveinsync.distance.engine.model.TripType;
import org.junit.jupiter.api.Test;

class NoShowAnchorTest {

    private static final RuleConfig FLAG_ON = RuleConfig.defaults().withIncludeNoShows(true);
    private static final RuleConfig FLAG_OFF = RuleConfig.defaults();

    private static AnchorResult build(RuleConfig config, EmployeeStop... stops) {
        return new AnchorBuilder(config).build(trip(TripType.LOGIN, stops));
    }

    private static EmployeeStop noShow(GeoPoint actual, GeoPoint planned) {
        return stop("E1", RiderType.PLANNED, StopEvent.NO_SHOW,
                actual, actual == null ? null : at(12), planned, at(10), null, null);
    }

    @Test
    void flagOffMeansNoShowsAddNothing() {
        AnchorResult result = build(FLAG_OFF, noShow(ACTUAL_A, PLANNED_A));

        assertEquals(0, employeeAnchors(result).size());
        assertEquals(0, result.skipped());
    }

    @Test
    void flagOnUsesTheNoShowLocation() {
        AnchorResult result = build(FLAG_ON, noShow(ACTUAL_A, PLANNED_A));

        assertEquals(PointSource.NO_SHOW_ACTUAL, employeeAnchors(result).get(0).source());
        assertEquals(ACTUAL_A, employeeAnchors(result).get(0).point());
        assertEquals(at(12), employeeAnchors(result).get(0).time());
    }

    @Test
    void flagOnFallsBackToPlannedWhenNoShowLocationIsInvalid() {
        AnchorResult result = build(FLAG_ON, noShow(new GeoPoint(0, 0), PLANNED_A));

        assertEquals(PointSource.NO_SHOW_PLANNED, employeeAnchors(result).get(0).source());
        assertEquals(PLANNED_A, employeeAnchors(result).get(0).point());
        assertEquals(at(10), employeeAnchors(result).get(0).time());
    }

    @Test
    void flagOnWithNothingUsableCountsAsSkipped() {
        AnchorResult result = build(FLAG_ON, noShow(null, null));

        assertEquals(0, employeeAnchors(result).size());
        assertEquals(1, result.skipped());
    }

    @Test
    void noShowsDoNotAffectEmployeesWhoTravelled() {
        EmployeeStop traveller = stop("E2", RiderType.PLANNED, StopEvent.PICKUP,
                ACTUAL_A, at(5), null, null, null, null);

        assertEquals(1, employeeAnchors(build(FLAG_OFF, traveller, noShow(ACTUAL_A, PLANNED_A))).size());
        assertEquals(2, employeeAnchors(build(FLAG_ON, traveller, noShow(ACTUAL_A, PLANNED_A))).size());
    }
}

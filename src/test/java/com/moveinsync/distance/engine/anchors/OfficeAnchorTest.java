package com.moveinsync.distance.engine.anchors;

import static com.moveinsync.distance.engine.anchors.TripFixtures.END;
import static com.moveinsync.distance.engine.anchors.TripFixtures.OFFICE;
import static com.moveinsync.distance.engine.anchors.TripFixtures.START;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.PointSource;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.TrackPoint;
import com.moveinsync.distance.engine.model.TripEvidence;
import com.moveinsync.distance.engine.model.TripType;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfficeAnchorTest {

    private static final double METERS_PER_DEGREE = 111_195.08;

    private final AnchorBuilder builder = new AnchorBuilder(RuleConfig.defaults());

    /** A point the given number of metres due north of the office. */
    private static GeoPoint northOfOffice(double meters) {
        return new GeoPoint(OFFICE.lat() + meters / METERS_PER_DEGREE, OFFICE.lon());
    }

    private TrackPoint officeAnchor(TripType type, GeoPoint actualStart, GeoPoint actualEnd) {
        TripEvidence trip = new TripEvidence(type, START, END, actualStart, actualEnd, OFFICE, List.of(), List.of());
        List<TrackPoint> anchors = builder.build(trip).anchors();
        assertEquals(1, anchors.size());
        return anchors.get(0);
    }

    @Test
    void loginTripEndingNearTheOfficeUsesTheActualEndPoint() {
        GeoPoint actualEnd = northOfOffice(399);

        TrackPoint anchor = officeAnchor(TripType.LOGIN, null, actualEnd);

        assertEquals(PointSource.OFFICE_ACTUAL, anchor.source());
        assertEquals(actualEnd, anchor.point());
        assertEquals(END, anchor.time());
    }

    @Test
    void loginTripEndingTooFarFromTheOfficeUsesThePlannedOffice() {
        TrackPoint anchor = officeAnchor(TripType.LOGIN, null, northOfOffice(401));

        assertEquals(PointSource.OFFICE_PLANNED, anchor.source());
        assertEquals(OFFICE, anchor.point());
        assertEquals(END, anchor.time());
    }

    @Test
    void missingEndPointUsesThePlannedOffice() {
        TrackPoint anchor = officeAnchor(TripType.LOGIN, null, null);

        assertEquals(PointSource.OFFICE_PLANNED, anchor.source());
        assertEquals(OFFICE, anchor.point());
    }

    @Test
    void invalidEndPointUsesThePlannedOffice() {
        TrackPoint anchor = officeAnchor(TripType.LOGIN, null, new GeoPoint(0, 0));

        assertEquals(PointSource.OFFICE_PLANNED, anchor.source());
    }

    @Test
    void logoutTripLooksAtTheStartPointAndStartTime() {
        GeoPoint actualStart = northOfOffice(50);

        TrackPoint anchor = officeAnchor(TripType.LOGOUT, actualStart, northOfOffice(5_000));

        assertEquals(PointSource.OFFICE_ACTUAL, anchor.source());
        assertEquals(actualStart, anchor.point());
        assertEquals(START, anchor.time());
    }

    @Test
    void logoutTripStartingFarAwayUsesPlannedOfficeAtStartTime() {
        TrackPoint anchor = officeAnchor(TripType.LOGOUT, northOfOffice(2_000), null);

        assertEquals(PointSource.OFFICE_PLANNED, anchor.source());
        assertEquals(OFFICE, anchor.point());
        assertEquals(START, anchor.time());
    }

    @Test
    void radiusIsConfigurable() {
        AnchorBuilder wide = new AnchorBuilder(
                new RuleConfig(120, 50, 70, 5, 1_000, 100, 1.4, false));
        TripEvidence trip = new TripEvidence(TripType.LOGIN, START, END, null,
                northOfOffice(800), OFFICE, List.of(), List.of());

        assertEquals(PointSource.OFFICE_ACTUAL, wide.build(trip).anchors().get(0).source());
    }
}

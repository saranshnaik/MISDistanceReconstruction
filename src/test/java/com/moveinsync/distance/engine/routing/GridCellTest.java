package com.moveinsync.distance.engine.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.model.GeoPoint;
import org.junit.jupiter.api.Test;

class GridCellTest {

    @Test
    void nearbyPointsShareACell() {
        assertEquals(GridCell.of(new GeoPoint(12.93521, 77.62451)), GridCell.of(new GeoPoint(12.93579, 77.62499)));
    }

    @Test
    void pointsOnEitherSideOfABoundaryAreInDifferentCells() {
        assertNotEquals(GridCell.of(new GeoPoint(12.9349, 77.6245)), GridCell.of(new GeoPoint(12.9351, 77.6245)));
    }

    @Test
    void centreOfACellSnapsBackToTheSameCell() {
        GridCell cell = GridCell.of(new GeoPoint(12.9352, 77.6245));

        assertEquals(cell, GridCell.of(cell.center()));
    }

    @Test
    void cellIsRoughly110MetresWide() {
        GridCell cell = GridCell.of(new GeoPoint(12.9352, 77.6245));
        GeoPoint centre = cell.center();
        GeoPoint nextNorth = new GridCell(cell.latIndex() + 1, cell.lonIndex()).center();

        double meters = Haversine.distanceM(centre, nextNorth);
        assertTrue(meters > 100 && meters < 120, "was " + meters);
    }

    @Test
    void negativeCoordinatesRoundDownConsistently() {
        // floor, not truncation: -0.0004 belongs to the cell just below zero
        assertEquals(-1, GridCell.of(new GeoPoint(-0.0004, 10)).latIndex());
        assertEquals(0, GridCell.of(new GeoPoint(0.0004, 10)).latIndex());
    }
}

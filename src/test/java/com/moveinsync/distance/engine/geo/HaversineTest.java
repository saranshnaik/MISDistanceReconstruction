package com.moveinsync.distance.engine.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.model.GeoPoint;
import org.junit.jupiter.api.Test;

class HaversineTest {

    @Test
    void samePointIsZero() {
        GeoPoint p = new GeoPoint(12.9716, 77.5946);
        assertEquals(0.0, Haversine.distanceM(p, p));
    }

    @Test
    void oneDegreeOfLatitudeIsAbout111Km() {
        double d = Haversine.distanceM(new GeoPoint(10, 77), new GeoPoint(11, 77));
        assertEquals(111_195.08, d, 0.01);
    }

    @Test
    void bangaloreToChennaiIsAbout290Km() {
        double km = Haversine.distanceM(new GeoPoint(12.9716, 77.5946), new GeoPoint(13.0827, 80.2707)) / 1000;
        assertTrue(km > 285 && km < 295, "was " + km);
    }

    @Test
    void distanceIsSymmetric() {
        GeoPoint a = new GeoPoint(12.9, 77.6);
        GeoPoint b = new GeoPoint(13.1, 77.9);
        assertEquals(Haversine.distanceM(a, b), Haversine.distanceM(b, a));
    }

    @Test
    void sameInputGivesBitIdenticalOutput() {
        GeoPoint a = new GeoPoint(12.93, 77.61);
        GeoPoint b = new GeoPoint(12.97, 77.75);
        assertEquals(Double.doubleToLongBits(Haversine.distanceM(a, b)),
                Double.doubleToLongBits(Haversine.distanceM(a, b)));
    }

    @Test
    void speedConversion() {
        assertEquals(36.0, Haversine.speedKmh(1000, 100), 1e-9);
        assertEquals(8100.0, Haversine.speedKmh(9000, 4), 1e-9);
    }
}

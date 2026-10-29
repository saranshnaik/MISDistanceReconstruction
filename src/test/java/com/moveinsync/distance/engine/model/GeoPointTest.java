package com.moveinsync.distance.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeoPointTest {

    @Test
    void normalPointIsValid() {
        assertTrue(new GeoPoint(12.9716, 77.5946).isValid());
    }

    @Test
    void outOfRangeIsInvalid() {
        assertFalse(new GeoPoint(91, 10).isValid());
        assertFalse(new GeoPoint(-91, 10).isValid());
        assertFalse(new GeoPoint(10, 181).isValid());
        assertFalse(new GeoPoint(10, -181).isValid());
    }

    @Test
    void nullIslandIsInvalid() {
        assertFalse(new GeoPoint(0, 0).isValid());
    }

    @Test
    void nanAndInfinityAreInvalid() {
        assertFalse(new GeoPoint(Double.NaN, 10).isValid());
        assertFalse(new GeoPoint(10, Double.POSITIVE_INFINITY).isValid());
    }

    @Test
    void defaultsMatchTheCaseStudy() {
        RuleConfig c = RuleConfig.defaults();
        assertEquals(120.0, c.maxSpeedKmh());
        assertEquals(70, c.shortGapSeconds());
        assertEquals(5.0, c.parkedSpeedKmh());
        assertEquals(400.0, c.officeRadiusM());
        assertEquals(100.0, c.maxRoutedKm());
        assertEquals(1.4, c.detourFactor());
        assertFalse(c.includeNoShows());
    }
}

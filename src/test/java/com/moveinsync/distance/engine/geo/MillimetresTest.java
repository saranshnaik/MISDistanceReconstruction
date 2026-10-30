package com.moveinsync.distance.engine.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MillimetresTest {

    @Test
    void metersRoundToTheNearestMillimetre() {
        assertEquals(1234, Millimetres.fromMeters(1.2344));
        assertEquals(1235, Millimetres.fromMeters(1.2346));
        assertEquals(0, Millimetres.fromMeters(0));
    }

    @Test
    void kmAreRoundedHalfUpToTwoDecimals() {
        assertEquals(new BigDecimal("1.01"), Millimetres.toKm(1_005_000)); // exactly 1.005 -> up
        assertEquals(new BigDecimal("1.00"), Millimetres.toKm(1_004_999));
        assertEquals(new BigDecimal("30.60"), Millimetres.toKm(30_600_000));
        assertEquals(new BigDecimal("0.00"), Millimetres.toKm(0));
    }

    @Test
    void additionOrderCannotChangeATotal() {
        long[] parts = {333_333, 666_667, 1, 999_999, 12};
        long forwards = 0;
        long backwards = 0;
        for (int i = 0; i < parts.length; i++) {
            forwards += parts[i];
            backwards += parts[parts.length - 1 - i];
        }
        assertEquals(forwards, backwards);
    }
}

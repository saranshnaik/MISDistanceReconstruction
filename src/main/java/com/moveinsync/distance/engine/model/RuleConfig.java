package com.moveinsync.distance.engine.model;

/**
 * Every threshold the rules use, in one place. Defaults are the values from the case study;
 * "accuracy" is our own choice (see README assumptions).
 */
public record RuleConfig(
        double maxSpeedKmh,
        double maxAccuracyM,
        long shortGapSeconds,
        double parkedSpeedKmh,
        double officeRadiusM,
        double maxRoutedKm,
        double detourFactor,
        boolean includeNoShows) {

    public static RuleConfig defaults() {
        return new RuleConfig(120.0, 50.0, 70, 5.0, 400.0, 100.0, 1.4, false);
    }

    public RuleConfig withMaxSpeedKmh(double value) {
        return new RuleConfig(value, maxAccuracyM, shortGapSeconds, parkedSpeedKmh,
                officeRadiusM, maxRoutedKm, detourFactor, includeNoShows);
    }

    public RuleConfig withMaxAccuracyM(double value) {
        return new RuleConfig(maxSpeedKmh, value, shortGapSeconds, parkedSpeedKmh,
                officeRadiusM, maxRoutedKm, detourFactor, includeNoShows);
    }

    public RuleConfig withIncludeNoShows(boolean value) {
        return new RuleConfig(maxSpeedKmh, maxAccuracyM, shortGapSeconds, parkedSpeedKmh,
                officeRadiusM, maxRoutedKm, detourFactor, value);
    }
}

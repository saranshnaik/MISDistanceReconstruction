package com.moveinsync.distance.bench;

import com.moveinsync.distance.engine.model.TripEvidence;

/**
 * One generated trip plus what we know about how it was made, so tests can compare the
 * engine's answer with the truth.
 *
 * @param routeId   which of the shared routes it drove
 * @param trueKm    the real length of the route driven (what a perfect GPS box would report)
 * @param hasBurst  whether a burst of bad fixes (9 km away) was injected
 */
public record SyntheticTrip(TripEvidence evidence, int routeId, double trueKm, boolean hasBurst) {
}

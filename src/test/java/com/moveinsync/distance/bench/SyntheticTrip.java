package com.moveinsync.distance.bench;

import com.moveinsync.distance.engine.model.TripEvidence;

/**
 * One generated trip plus how it was made (shared route, true length, whether a burst of
 * bad fixes was injected), so tests can compare the engine's answer with the truth.
 */
public record SyntheticTrip(TripEvidence evidence, int routeId, double trueKm, boolean hasBurst) {
}

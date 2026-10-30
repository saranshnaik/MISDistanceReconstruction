package com.moveinsync.distance.engine.routing;

import com.moveinsync.distance.engine.model.GeoPoint;
import java.util.ArrayList;
import java.util.List;

/**
 * Whoever knows the driving distance between two points (a maps API in real life).
 * The engine only sees this interface, so caching, circuit breaking and the fake provider
 * used in tests can all be plugged in behind it.
 */
public interface RoutingProvider {

    /** Driving distance in metres from {@code from} to {@code to}. */
    double drivingDistanceM(GeoPoint from, GeoPoint to);

    /**
     * Answers many questions in one go, in the same order. Real routing APIs take a whole
     * matrix per request, which is much cheaper than one request per pair. The default just
     * asks one by one; a provider with a real batch endpoint should override it.
     */
    default List<Double> drivingDistancesM(List<RoutePair> pairs) {
        List<Double> answers = new ArrayList<>(pairs.size());
        for (RoutePair pair : pairs) {
            answers.add(drivingDistanceM(pair.from(), pair.to()));
        }
        return answers;
    }
}

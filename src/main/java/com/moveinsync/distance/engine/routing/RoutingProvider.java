package com.moveinsync.distance.engine.routing;

import com.moveinsync.distance.engine.model.GeoPoint;
import java.util.ArrayList;
import java.util.List;

/**
 * Whoever knows driving distances (a maps API in real life). The engine only sees this, so
 * caching, circuit breaking and the fake provider all plug in behind it.
 */
public interface RoutingProvider {

    /** Driving distance in metres. */
    double drivingDistanceM(GeoPoint from, GeoPoint to);

    /**
     * Many questions in one go, same order. Real APIs take a whole matrix per request, much
     * cheaper than one call per pair. This default asks one by one; override it if the provider
     * has a batch endpoint.
     */
    default List<Double> drivingDistancesM(List<RoutePair> pairs) {
        List<Double> answers = new ArrayList<>(pairs.size());
        for (RoutePair pair : pairs) {
            answers.add(drivingDistanceM(pair.from(), pair.to()));
        }
        return answers;
    }
}

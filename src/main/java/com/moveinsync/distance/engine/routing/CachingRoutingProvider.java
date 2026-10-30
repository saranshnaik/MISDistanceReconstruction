package com.moveinsync.distance.engine.routing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.model.GeoPoint;
import java.time.Duration;

/**
 * Remembers routing answers so we do not pay twice for the same stretch of road.
 *
 * The cache key is the pair of grid cells (start cell, end cell), not the exact coordinates,
 * because the same commute from slightly different GPS points is the same road.
 *
 * Important for billing: the provider is ALWAYS asked about the cell centres, never about the
 * exact points. So a cache hit and a cache miss give exactly the same number, and caching can
 * never change a bill. The price is up to roughly 150 m of position error on a routed gap.
 *
 * If start and end fall in the same cell (closer than ~110 m) routing would say 0, so we
 * return the straight line instead and do not call the provider at all.
 *
 * A failed call is not cached: the exception goes to the caller and the next call tries again.
 * Entries expire after 24 hours so a changed road network is eventually picked up.
 */
public class CachingRoutingProvider implements RoutingProvider {

    static final int MAX_ENTRIES = 100_000;
    static final Duration TTL = Duration.ofHours(24);

    private record CellPair(GridCell from, GridCell to) {
    }

    private final RoutingProvider delegate;
    private final Cache<CellPair, Double> cache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(TTL)
            .recordStats()
            .build();

    public CachingRoutingProvider(RoutingProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public double drivingDistanceM(GeoPoint from, GeoPoint to) {
        GridCell fromCell = GridCell.of(from);
        GridCell toCell = GridCell.of(to);
        if (fromCell.equals(toCell)) {
            return Haversine.distanceM(from, to);
        }
        // get(key, loader) runs the loader once per key even if many threads ask at the same time
        return cache.get(new CellPair(fromCell, toCell),
                pair -> delegate.drivingDistanceM(pair.from().center(), pair.to().center()));
    }

    public long hits() {
        return cache.stats().hitCount();
    }

    public long misses() {
        return cache.stats().missCount();
    }

    public long size() {
        return cache.estimatedSize();
    }
}

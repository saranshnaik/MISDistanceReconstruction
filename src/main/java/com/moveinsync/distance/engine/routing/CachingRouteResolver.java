package com.moveinsync.distance.engine.routing;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moveinsync.distance.engine.geo.Haversine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cuts routing cost three ways, in this order:
 *
 *  1. snap: each gap is reduced to a pair of grid cells (start cell, end cell)
 *  2. dedupe + cache: a cell pair seen before, in this trip or any earlier one, costs nothing
 *  3. batch: the cell pairs we still do not know are sent in requests of at most 25 pairs
 *
 * For billing safety the provider is ALWAYS asked about the cell centres, never the exact
 * points. So a cache hit and a miss give exactly the same number, and caching can never change
 * a bill. The price is up to roughly 150 m of position error on a routed gap.
 *
 * Start and end in the same cell (closer than ~110 m) would route centre-to-centre as 0, so
 * those get the straight line and cost no request.
 *
 * Several trips asking for the same new route at the same moment share one request (the cache
 * holds the in-flight answer). A failed request is not cached; everyone waiting gets the error
 * and the next call tries again. Entries expire after 24 hours so a changed road network is
 * eventually picked up.
 */
public class CachingRouteResolver implements RouteResolver {

    static final int MAX_ENTRIES = 100_000;
    static final Duration TTL = Duration.ofHours(24);
    static final int BATCH_SIZE = 25;

    private record CellPair(GridCell from, GridCell to) {
    }

    private static final Comparator<CellPair> STABLE_ORDER =
            Comparator.comparingLong((CellPair p) -> p.from().latIndex())
                    .thenComparingLong(p -> p.from().lonIndex())
                    .thenComparingLong(p -> p.to().latIndex())
                    .thenComparingLong(p -> p.to().lonIndex());

    private final RoutingProvider provider;
    private final AsyncCache<CellPair, Double> cache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(TTL)
            .buildAsync();
    private final AtomicLong totalHits = new AtomicLong();
    private final AtomicLong totalLoads = new AtomicLong();

    public CachingRouteResolver(RoutingProvider provider) {
        this.provider = provider;
    }

    @Override
    public RouteResolution resolve(List<RoutePair> pairs) {
        double[] meters = new double[pairs.size()];
        CellPair[] keys = new CellPair[pairs.size()];
        Set<CellPair> distinct = new LinkedHashSet<>();

        for (int i = 0; i < pairs.size(); i++) {
            RoutePair pair = pairs.get(i);
            GridCell fromCell = GridCell.of(pair.from());
            GridCell toCell = GridCell.of(pair.to());
            if (fromCell.equals(toCell)) {
                meters[i] = Haversine.distanceM(pair.from(), pair.to());
            } else {
                keys[i] = new CellPair(fromCell, toCell);
                distinct.add(keys[i]);
            }
        }

        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger loadedHere = new AtomicInteger();
        Map<CellPair, Double> known = new HashMap<>();
        if (!distinct.isEmpty()) {
            known = lookUp(distinct, providerCalls, loadedHere);
        }
        int hits = distinct.size() - loadedHere.get();
        totalHits.addAndGet(hits);
        totalLoads.addAndGet(loadedHere.get());

        List<Double> answers = new ArrayList<>(pairs.size());
        for (int i = 0; i < pairs.size(); i++) {
            answers.add(keys[i] == null ? meters[i] : known.get(keys[i]));
        }
        return new RouteResolution(answers, providerCalls.get(), hits);
    }

    private Map<CellPair, Double> lookUp(Set<CellPair> wanted, AtomicInteger providerCalls,
                                         AtomicInteger loadedHere) {
        CompletableFuture<Map<CellPair, Double>> future = cache.getAll(wanted, (missing, executor) -> {
            loadedHere.set(missing.size());
            try {
                return CompletableFuture.completedFuture(loadInBatches(missing, providerCalls));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        });
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** Sorted first so the same set of missing routes always makes the same requests. */
    private Map<CellPair, Double> loadInBatches(Collection<? extends CellPair> missing,
                                                AtomicInteger providerCalls) {
        List<CellPair> ordered = new ArrayList<>(missing);
        ordered.sort(STABLE_ORDER);

        Map<CellPair, Double> loaded = new HashMap<>();
        for (int start = 0; start < ordered.size(); start += BATCH_SIZE) {
            List<CellPair> chunk = ordered.subList(start, Math.min(start + BATCH_SIZE, ordered.size()));
            List<RoutePair> questions = new ArrayList<>(chunk.size());
            for (CellPair key : chunk) {
                questions.add(new RoutePair(key.from().center(), key.to().center()));
            }
            providerCalls.incrementAndGet();
            List<Double> answers = provider.drivingDistancesM(questions);
            for (int i = 0; i < chunk.size(); i++) {
                loaded.put(chunk.get(i), answers.get(i));
            }
        }
        return loaded;
    }

    /** Distinct routes served from the cache so far (all trips together). */
    public long hits() {
        return totalHits.get();
    }

    /** Distinct routes that had to be fetched so far (all trips together). */
    public long misses() {
        return totalLoads.get();
    }

    public long size() {
        return cache.synchronous().estimatedSize();
    }
}

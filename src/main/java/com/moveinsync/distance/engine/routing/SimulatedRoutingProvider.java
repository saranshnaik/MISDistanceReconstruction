package com.moveinsync.distance.engine.routing;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.model.GeoPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A stand-in for a real maps API, so the project runs and tests without keys or internet.
 *
 * Roads are never straight, so the "driving distance" is the straight line times a detour
 * factor between 1.25 and 1.45. The factor comes from the coordinates themselves, which means
 * the same two points always give the same answer, on any run.
 *
 * It behaves like the real thing in the ways that matter here: every request is counted (calls
 * cost money), it can be slow, and it can be switched off to simulate an outage. A batch of
 * pairs is ONE request, like a distance-matrix endpoint. A real provider (OSRM, Google...)
 * would implement the same RoutingProvider interface.
 */
public class SimulatedRoutingProvider implements RoutingProvider {

    private static final double MIN_DETOUR = 1.25;
    private static final int DETOUR_STEPS = 21; // 1.25, 1.26 ... 1.45

    private final long latencyMs;
    private final AtomicBoolean down = new AtomicBoolean(false);
    private final AtomicLong calls = new AtomicLong();

    public SimulatedRoutingProvider() {
        this(0);
    }

    public SimulatedRoutingProvider(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    @Override
    public double drivingDistanceM(GeoPoint from, GeoPoint to) {
        return drivingDistancesM(List.of(new RoutePair(from, to))).get(0);
    }

    @Override
    public List<Double> drivingDistancesM(List<RoutePair> pairs) {
        calls.incrementAndGet();
        if (latencyMs > 0) {
            sleep(latencyMs);
        }
        if (down.get()) {
            throw new RoutingUnavailableException("routing provider is not responding");
        }
        List<Double> answers = new ArrayList<>(pairs.size());
        for (RoutePair pair : pairs) {
            answers.add(Haversine.distanceM(pair.from(), pair.to()) * detourFactor(pair.from(), pair.to()));
        }
        return answers;
    }

    /** Pretend the provider is (or is no longer) down. */
    public void setDown(boolean value) {
        down.set(value);
    }

    public boolean isDown() {
        return down.get();
    }

    /** Every request counts, including the ones that failed: that is how real APIs bill. */
    public long callCount() {
        return calls.get();
    }

    private static double detourFactor(GeoPoint from, GeoPoint to) {
        int hash = Objects.hash(from.lat(), from.lon(), to.lat(), to.lon());
        return MIN_DETOUR + Math.floorMod(hash, DETOUR_STEPS) / 100.0;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RoutingUnavailableException("interrupted while waiting for the routing provider", e);
        }
    }
}

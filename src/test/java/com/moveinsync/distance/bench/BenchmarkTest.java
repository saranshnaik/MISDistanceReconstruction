package com.moveinsync.distance.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.DistanceEngine;
import com.moveinsync.distance.engine.TripDistanceResult;
import com.moveinsync.distance.engine.TripStatus;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.routing.CachingRouteResolver;
import com.moveinsync.distance.engine.routing.RouteResolver;
import com.moveinsync.distance.engine.routing.SimulatedRoutingProvider;
import com.moveinsync.distance.engine.routing.DirectRouteResolver;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The scale test from the case study: 10,000 synthetic trips of about 1,500 pings each.
 *
 * Not part of the normal test run (it takes a minute or two). Run it with:
 *     ./mvnw test -Pbenchmark
 * The report is printed and also written to target/benchmark-report.md.
 *
 * What is measured, and what is not:
 *  - Trips are generated one at a time and thrown away, so memory reflects the engine, not
 *    ten thousand trips held at once. Generation time is NOT in the latency numbers.
 *  - The routing provider here answers instantly. Real providers take tens of milliseconds
 *    per call, so the report also shows calls x an assumed 30 ms. That column is arithmetic,
 *    not a measurement.
 */
@Tag("benchmark")
class BenchmarkTest {

    private static final int TRIPS = 10_000;
    private static final long SEED = 42;
    private static final double ASSUMED_PROVIDER_MILLIS = 30.0;

    /** Everything one scenario produced. */
    private record Result(String name, int threads, long wallMillis, long[] tripNanos, long providerCalls,
                          long routeRequests, long cacheHits, long totalMm, int degraded, long peakUsedHeapBytes,
                          long pingsProcessed) {

        double percentileMillis(double p) {
            long[] sorted = tripNanos.clone();
            Arrays.sort(sorted);
            return sorted[(int) Math.min(sorted.length - 1, Math.floor(p * sorted.length))] / 1e6;
        }

        double meanMillis() {
            return Arrays.stream(tripNanos).average().orElse(0) / 1e6;
        }

        double tripsPerSecond() {
            return TRIPS * 1000.0 / wallMillis;
        }
    }

    @Test
    void tenThousandTripsOfFifteenHundredPings() throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();

        Result cacheOff = run("cache OFF, 1 thread", new DirectRouteResolverFactory(), 1);
        Result cacheOn = run("cache ON,  1 thread", new CachingResolverFactory(), 1);
        Result parallel = run("cache ON, " + cores + " threads", new CachingResolverFactory(), cores);

        String report = report(List.of(cacheOff, cacheOn, parallel), cores);
        System.out.println(report);
        Files.writeString(Path.of("target", "benchmark-report.md"), report);

        // every trip was priced normally
        for (Result r : List.of(cacheOff, cacheOn, parallel)) {
            assertEquals(0, r.degraded(), r.name());
        }
        // the cache is what saves the money
        assertTrue(cacheOn.providerCalls() * 10 < cacheOff.providerCalls(),
                "cache should cut provider calls by 10x or more: " + cacheOff.providerCalls() + " -> "
                        + cacheOn.providerCalls());
        // same trips, same rules: running in parallel must not change a single millimetre
        assertEquals(cacheOn.totalMm(), parallel.totalMm());
        // a very generous ceiling, only to catch an accidental O(n^2)
        assertTrue(cacheOn.wallMillis() < 5 * 60_000L, "took " + cacheOn.wallMillis() + " ms");
    }

    // ---- running a scenario ----

    private interface ResolverFactory {
        RouteResolver create(SimulatedRoutingProvider provider);
    }

    private static final class DirectRouteResolverFactory implements ResolverFactory {
        @Override
        public RouteResolver create(SimulatedRoutingProvider provider) {
            return new DirectRouteResolver(provider);
        }
    }

    private static final class CachingResolverFactory implements ResolverFactory {
        @Override
        public RouteResolver create(SimulatedRoutingProvider provider) {
            return new CachingRouteResolver(provider);
        }
    }

    private Result run(String name, ResolverFactory factory, int threads) throws Exception {
        SimulatedRoutingProvider provider = new SimulatedRoutingProvider();
        DistanceEngine engine = new DistanceEngine(RuleConfig.defaults(), factory.create(provider));
        SyntheticTrips generator = new SyntheticTrips(SEED);

        System.gc();
        AtomicLong peakHeap = new AtomicLong();
        Thread sampler = new Thread(() -> {
            Runtime rt = Runtime.getRuntime();
            while (!Thread.currentThread().isInterrupted()) {
                peakHeap.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), Math::max);
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();

        long[] tripNanos = new long[TRIPS];
        AtomicLong routeRequests = new AtomicLong();
        AtomicLong cacheHits = new AtomicLong();
        AtomicLong totalMm = new AtomicLong();
        AtomicLong degraded = new AtomicLong();
        AtomicLong pings = new AtomicLong();

        long started = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int offset = t;
            final int step = threads;
            futures.add(pool.submit(() -> {
                for (int i = offset; i < TRIPS; i += step) {
                    SyntheticTrip trip = generator.trip(i); // not timed
                    long begin = System.nanoTime();
                    TripDistanceResult result = engine.compute(trip.evidence());
                    tripNanos[i] = System.nanoTime() - begin;

                    routeRequests.addAndGet(result.routing().routeRequests());
                    cacheHits.addAndGet(result.routing().cacheHits());
                    totalMm.addAndGet(result.totalMm());
                    pings.addAndGet(trip.evidence().pings().size());
                    if (result.status() != TripStatus.OK) {
                        degraded.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();
        long wallMillis = (System.nanoTime() - started) / 1_000_000;
        sampler.interrupt();

        return new Result(name, threads, wallMillis, tripNanos, provider.callCount(), routeRequests.get(),
                cacheHits.get(), totalMm.get(), (int) degraded.get(), peakHeap.get(), pings.get());
    }

    // ---- the report ----

    private String report(List<Result> results, int cores) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# Benchmark: ").append(String.format("%,d", TRIPS)).append(" synthetic trips\n\n");
        out.append(machine(cores)).append('\n');
        out.append(String.format("Average pings per trip: %,d. Seed %d, 40 shared routes.%n%n",
                results.get(0).pingsProcessed() / TRIPS, SEED));

        out.append("| scenario | wall time | trips/s | engine p50 | engine p99 | engine mean | peak heap |\n");
        out.append("|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            out.append(String.format("| %s | %.1f s | %,.0f | %.2f ms | %.2f ms | %.2f ms | %,d MB |%n",
                    r.name(), r.wallMillis() / 1000.0, r.tripsPerSecond(), r.percentileMillis(0.50),
                    r.percentileMillis(0.99), r.meanMillis(), r.peakUsedHeapBytes() / (1024 * 1024)));
        }

        out.append("\n| scenario | gaps needing a route | provider calls | cache hits | est. provider wait at ")
                .append((int) ASSUMED_PROVIDER_MILLIS).append(" ms/call |\n|---|---|---|---|---|\n");
        for (Result r : results) {
            out.append(String.format("| %s | %,d | %,d | %,d | %,.0f s |%n", r.name(), r.routeRequests(),
                    r.providerCalls(), r.cacheHits(), r.providerCalls() * ASSUMED_PROVIDER_MILLIS / 1000.0));
        }

        Result off = results.get(0);
        Result on = results.get(1);
        out.append(String.format("%nProvider calls before caching: %,d. After caching and batching: %,d (%.1f%% fewer).%n",
                off.providerCalls(), on.providerCalls(), 100.0 * (1 - (double) on.providerCalls() / off.providerCalls())));
        out.append(String.format("Total distance, cache on, 1 thread vs %d threads: %,d mm vs %,d mm (%s).%n",
                results.get(2).threads(), on.totalMm(), results.get(2).totalMm(),
                on.totalMm() == results.get(2).totalMm() ? "identical" : "DIFFERENT"));
        return out.toString();
    }

    private String machine(int cores) {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        String cpu = System.getenv("PROCESSOR_IDENTIFIER");
        return String.format("Machine: %s %s, %s, %d logical cores, %,d MB RAM, JVM %s %s, max heap %,d MB%n",
                System.getProperty("os.name"), System.getProperty("os.version"), cpu == null ? "unknown CPU" : cpu,
                cores, os.getTotalMemorySize() / (1024 * 1024), System.getProperty("java.vm.name"),
                System.getProperty("java.version"), Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }
}

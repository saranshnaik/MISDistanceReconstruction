package com.moveinsync.distance.engine;

import com.moveinsync.distance.engine.anchors.AnchorBuilder;
import com.moveinsync.distance.engine.anchors.AnchorResult;
import com.moveinsync.distance.engine.cleaning.CleanedPings;
import com.moveinsync.distance.engine.cleaning.PingCleaner;
import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.geo.Millimetres;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.PointSource;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.model.Segment;
import com.moveinsync.distance.engine.model.TrackPoint;
import com.moveinsync.distance.engine.model.TripEvidence;
import com.moveinsync.distance.engine.routing.DirectRouteResolver;
import com.moveinsync.distance.engine.routing.RoutePair;
import com.moveinsync.distance.engine.routing.RouteResolution;
import com.moveinsync.distance.engine.routing.RouteResolver;
import com.moveinsync.distance.engine.routing.RoutingProvider;
import com.moveinsync.distance.engine.routing.RoutingUnavailableException;
import com.moveinsync.distance.engine.rules.ParkedRule;
import com.moveinsync.distance.engine.rules.RoutedDistancePolicy;
import com.moveinsync.distance.engine.rules.SegmentDistanceRule;
import com.moveinsync.distance.engine.rules.ShortGapAerialRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One trip, start to finish: clean the pings, build anchors, merge into one track, price each
 * neighbouring pair with the cheap rules, and send whatever is left (the real gaps) to the
 * router in one go so it can dedupe, cache and batch. Then the routing policy accepts or
 * rejects each answer, and the billed millimetres are added up and rounded to km once.
 * If routing is down the gaps get an estimate and the trip is marked DEGRADED.
 *
 * No Spring, no database in here. Same TripEvidence + same RuleConfig gives the same result;
 * the provider is the only outside input.
 */
public class DistanceEngine {

    /** Same instant: GPS first, then a fixed order, so input order never matters. */
    private static final Comparator<TrackPoint> TRACK_ORDER =
            Comparator.comparing(TrackPoint::time)
                    .thenComparing(TrackPoint::source)
                    .thenComparing(TrackPoint::ref, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparingDouble(p -> p.point().lat())
                    .thenComparingDouble(p -> p.point().lon());

    private final RuleConfig config;
    private final PingCleaner cleaner;
    private final AnchorBuilder anchorBuilder;
    /** Cheap rules, tried in this order. The order is part of the rule version. */
    private final List<SegmentDistanceRule> cheapRules;
    private final RoutedDistancePolicy routedPolicy;
    private final RouteResolver routeResolver;
    private final String ruleVersion;

    /** One provider call per gap, no cache. Handy in tests and as the "before" number. */
    public DistanceEngine(RuleConfig config, RoutingProvider routingProvider) {
        this(config, new DirectRouteResolver(routingProvider));
    }

    public DistanceEngine(RuleConfig config, RouteResolver routeResolver) {
        this.config = config;
        this.cleaner = new PingCleaner(config);
        this.anchorBuilder = new AnchorBuilder(config);
        this.cheapRules = List.of(new ShortGapAerialRule(config), new ParkedRule(config));
        this.routedPolicy = new RoutedDistancePolicy(config);
        this.routeResolver = routeResolver;
        this.ruleVersion = RuleVersion.of(config);
    }

    public TripDistanceResult compute(TripEvidence trip) {
        CleanedPings cleaned = cleaner.clean(trip.pings(), trip.tripStart(), trip.tripEnd());
        AnchorResult anchors = anchorBuilder.build(trip);

        List<TrackPoint> pingPoints = toTrackPoints(cleaned.pings());
        List<TrackPoint> track = new ArrayList<>(pingPoints);
        track.addAll(anchors.anchors());
        track.sort(TRACK_ORDER);

        // segment i is the stretch between track[i] and track[i + 1]
        Segment[] segments = new Segment[Math.max(0, track.size() - 1)];
        List<Integer> needsRoute = new ArrayList<>();
        List<RoutePair> questions = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            Optional<Segment> cheap = priceWithCheapRules(track.get(i), track.get(i + 1));
            if (cheap.isPresent()) {
                segments[i] = cheap.get();
            } else {
                needsRoute.add(i);
                questions.add(new RoutePair(track.get(i).point(), track.get(i + 1).point()));
            }
        }

        RoutingUsage usage = new RoutingUsage(questions.size(), 0, 0);
        TripStatus status = TripStatus.OK;
        if (!questions.isEmpty()) {
            try {
                RouteResolution resolution = routeResolver.resolve(questions);
                for (int k = 0; k < needsRoute.size(); k++) {
                    int i = needsRoute.get(k);
                    segments[i] = routedPolicy.judge(track.get(i), track.get(i + 1), resolution.meters().get(k));
                }
                usage = new RoutingUsage(questions.size(), resolution.providerCalls(), resolution.cacheHits());
            } catch (RoutingUnavailableException e) {
                // provider down or circuit open: estimate the gaps and flag the trip
                status = TripStatus.DEGRADED;
                for (int i : needsRoute) {
                    segments[i] = routedPolicy.degradedEstimate(track.get(i), track.get(i + 1));
                }
            }
        }

        long totalMm = 0;
        for (Segment segment : segments) {
            totalMm += segment.billedMm();
        }

        return new TripDistanceResult(
                List.copyOf(Arrays.asList(segments)),
                totalMm,
                Millimetres.toKm(totalMm),
                Millimetres.toKm(naiveMm(pingPoints)),
                cleaned.report(),
                anchors.skipped(),
                usage,
                status,
                ruleVersion);
    }

    private Optional<Segment> priceWithCheapRules(TrackPoint from, TrackPoint to) {
        for (SegmentDistanceRule rule : cheapRules) {
            Optional<Segment> segment = rule.apply(from, to);
            if (segment.isPresent()) {
                return segment;
            }
        }
        return Optional.empty();
    }

    private static List<TrackPoint> toTrackPoints(List<Ping> pings) {
        List<TrackPoint> points = new ArrayList<>(pings.size());
        for (Ping ping : pings) {
            points.add(new TrackPoint(ping.time(), ping.point(), PointSource.GPS_PING, null));
        }
        return points;
    }

    /**
     * Straight lines between consecutive cleaned pings, i.e. the "just add up the pings" answer.
     */
    private static long naiveMm(List<TrackPoint> pings) {
        long total = 0;
        for (int i = 1; i < pings.size(); i++) {
            total += Millimetres.fromMeters(Haversine.distanceM(pings.get(i - 1).point(), pings.get(i).point()));
        }
        return total;
    }

    public String ruleVersion() {
        return ruleVersion;
    }

    public RuleConfig config() {
        return config;
    }
}

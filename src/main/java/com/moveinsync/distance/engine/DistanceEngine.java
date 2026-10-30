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
import com.moveinsync.distance.engine.routing.RoutingProvider;
import com.moveinsync.distance.engine.rules.ParkedRule;
import com.moveinsync.distance.engine.rules.RoutedDistancePolicy;
import com.moveinsync.distance.engine.rules.SegmentDistanceRule;
import com.moveinsync.distance.engine.rules.ShortGapAerialRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The whole pipeline for one trip, in a fixed order:
 *
 *  1. clean the pings
 *  2. build the anchors
 *  3. merge both into one time-ordered track
 *  4. price every pair of neighbouring points: ShortGapAerial, then Parked, otherwise ask the
 *     routing provider and let RoutedDistancePolicy accept or reject the answer
 *  5. add up the billed millimetres and round to km once
 *
 * Plain Java, no Spring or database. The same TripEvidence and the same RuleConfig always give
 * the same result; the only outside input is the RoutingProvider.
 */
public class DistanceEngine {

    /** Same instant? GPS first, then a fixed order, so the track never depends on input order. */
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
    private final RoutingProvider routingProvider;
    private final String ruleVersion;

    public DistanceEngine(RuleConfig config, RoutingProvider routingProvider) {
        this.config = config;
        this.cleaner = new PingCleaner(config);
        this.anchorBuilder = new AnchorBuilder(config);
        this.cheapRules = List.of(new ShortGapAerialRule(config), new ParkedRule(config));
        this.routedPolicy = new RoutedDistancePolicy(config);
        this.routingProvider = routingProvider;
        this.ruleVersion = RuleVersion.of(config);
    }

    public TripDistanceResult compute(TripEvidence trip) {
        CleanedPings cleaned = cleaner.clean(trip.pings(), trip.tripStart(), trip.tripEnd());
        AnchorResult anchors = anchorBuilder.build(trip);

        List<TrackPoint> pingPoints = toTrackPoints(cleaned.pings());
        List<TrackPoint> track = new ArrayList<>(pingPoints);
        track.addAll(anchors.anchors());
        track.sort(TRACK_ORDER);

        List<Segment> segments = new ArrayList<>(Math.max(0, track.size() - 1));
        long totalMm = 0;
        for (int i = 1; i < track.size(); i++) {
            Segment segment = price(track.get(i - 1), track.get(i));
            segments.add(segment);
            totalMm += segment.billedMm();
        }

        return new TripDistanceResult(
                segments,
                totalMm,
                Millimetres.toKm(totalMm),
                Millimetres.toKm(naiveMm(pingPoints)),
                cleaned.report(),
                anchors.skipped(),
                ruleVersion);
    }

    private Segment price(TrackPoint from, TrackPoint to) {
        for (SegmentDistanceRule rule : cheapRules) {
            Optional<Segment> segment = rule.apply(from, to);
            if (segment.isPresent()) {
                return segment.get();
            }
        }
        double routedMeters = routingProvider.drivingDistanceM(from.point(), to.point());
        return routedPolicy.judge(from, to, routedMeters);
    }

    private static List<TrackPoint> toTrackPoints(List<Ping> pings) {
        List<TrackPoint> points = new ArrayList<>(pings.size());
        for (Ping ping : pings) {
            points.add(new TrackPoint(ping.time(), ping.point(), PointSource.GPS_PING, null));
        }
        return points;
    }

    /** Straight lines between consecutive cleaned pings: the "just add up the pings" answer. */
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

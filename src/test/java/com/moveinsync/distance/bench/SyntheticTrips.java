package com.moveinsync.distance.bench;

import com.moveinsync.distance.engine.geo.Haversine;
import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.RiderType;
import com.moveinsync.distance.engine.model.StopEvent;
import com.moveinsync.distance.engine.model.TripEvidence;
import com.moveinsync.distance.engine.model.TripType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Makes believable trips for tests and the benchmark. Everything comes from the seed, so the
 * same seed gives the same trips on any machine.
 *
 * The world: about 40 routes across a city-sized box. Many trips drive the same route (as real
 * commutes do), and every route has fixed "blackout zones" (a tunnel, a basement car park)
 * where GPS is lost, with employee drop-offs inside them. So the routing questions repeat
 * between trips and the cache has something realistic to work with.
 *
 * A trip: about 3000 seconds long with a ping every 2 seconds, so about 1500 pings, then mess
 * is added the way real devices do it:
 *   - 2% of pings are sent twice
 *   - 1% have terrible accuracy
 *   - 30% of trips get a burst of three bad fixes 9 km away
 *   - 5% of trips have pings from before the trip started
 *   - the whole list arrives in random order
 */
public class SyntheticTrips {

    static final int ROUTE_COUNT = 40;
    private static final double METERS_PER_DEGREE = 111_195.08;
    private static final Instant BASE_TIME = Instant.parse("2026-03-01T06:00:00Z");

    /** A fixed path through the city with its blackout zones and the places employees get dropped. */
    record Route(int id, List<GeoPoint> waypoints, double[] cumulativeMeters, double lengthMeters,
                 double[][] blackouts, double[] stopDistances) {

        GeoPoint at(double meters) {
            double m = Math.max(0, Math.min(lengthMeters, meters));
            int i = 1;
            while (i < cumulativeMeters.length - 1 && cumulativeMeters[i] < m) {
                i++;
            }
            double segmentLength = cumulativeMeters[i] - cumulativeMeters[i - 1];
            double fraction = segmentLength == 0 ? 0 : (m - cumulativeMeters[i - 1]) / segmentLength;
            GeoPoint a = waypoints.get(i - 1);
            GeoPoint b = waypoints.get(i);
            return new GeoPoint(a.lat() + (b.lat() - a.lat()) * fraction, a.lon() + (b.lon() - a.lon()) * fraction);
        }

        boolean inBlackout(double meters) {
            for (double[] zone : blackouts) {
                if (meters >= zone[0] && meters <= zone[1]) {
                    return true;
                }
            }
            return false;
        }
    }

    private final long seed;
    private final List<Route> routes;

    public SyntheticTrips(long seed) {
        this.seed = seed;
        Random rnd = new Random(seed);
        List<Route> built = new ArrayList<>();
        for (int id = 0; id < ROUTE_COUNT; id++) {
            built.add(buildRoute(id, rnd));
        }
        this.routes = List.copyOf(built);
    }

    private static Route buildRoute(int id, Random rnd) {
        double lat = 12.85 + rnd.nextDouble() * 0.20;
        double lon = 77.50 + rnd.nextDouble() * 0.20;
        double bearing = rnd.nextDouble() * 2 * Math.PI;
        List<GeoPoint> points = new ArrayList<>();
        points.add(new GeoPoint(lat, lon));
        for (int leg = 0; leg < 6; leg++) {
            double meters = 3_500 + rnd.nextDouble() * 1_500;
            bearing += (rnd.nextDouble() - 0.5) * 1.2; // gentle turns
            lat += meters * Math.cos(bearing) / METERS_PER_DEGREE;
            lon += meters * Math.sin(bearing) / (METERS_PER_DEGREE * Math.cos(Math.toRadians(lat)));
            points.add(new GeoPoint(lat, lon));
        }
        double[] cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            cumulative[i] = cumulative[i - 1] + Haversine.distanceM(points.get(i - 1), points.get(i));
        }
        double length = cumulative[cumulative.length - 1];

        double firstStart = length * (0.25 + rnd.nextDouble() * 0.15);
        double firstWidth = 1_500 + rnd.nextDouble() * 1_000;
        double secondStart = length * (0.60 + rnd.nextDouble() * 0.15);
        double secondWidth = 600 + rnd.nextDouble() * 600;
        double[][] blackouts = {{firstStart, firstStart + firstWidth}, {secondStart, secondStart + secondWidth}};
        double[] stops = {length * 0.15, firstStart + firstWidth * 0.3, firstStart + firstWidth * 0.7};
        return new Route(id, points, cumulative, length, blackouts, stops);
    }

    public int routeCount() {
        return routes.size();
    }

    /** Trip number {@code index}: always the same trip for the same seed and index. */
    public SyntheticTrip trip(int index) {
        Route route = routes.get(index % routes.size());
        Random rnd = new Random(seed * 1_000_003L + index);

        int durationSeconds = 2 * (int) Math.round((3000 + (rnd.nextDouble() - 0.5) * 600) / 2); // 2700..3300, even
        Instant start = BASE_TIME.plusSeconds(60L * index);
        Instant end = start.plusSeconds(durationSeconds);

        List<Ping> pings = new ArrayList<>(durationSeconds / 2 + 50);
        for (int t = 0; t <= durationSeconds; t += 2) {
            double meters = route.lengthMeters() * t / durationSeconds;
            if (route.inBlackout(meters)) {
                continue; // underpass, basement: no signal
            }
            double accuracy = rnd.nextDouble() < 0.01 ? 80 + rnd.nextDouble() * 120 : 3 + rnd.nextDouble() * 9;
            Ping ping = new Ping(start.plusSeconds(t), jitter(route.at(meters), rnd), accuracy);
            pings.add(ping);
            if (rnd.nextDouble() < 0.02) {
                pings.add(ping); // device sent it twice
            }
        }

        boolean burst = rnd.nextDouble() < 0.30;
        if (burst) {
            int at = 1 + rnd.nextInt(Math.max(1, pings.size() - 2));
            Ping anchor = pings.get(at);
            GeoPoint farAway = new GeoPoint(anchor.point().lat() + 0.081, anchor.point().lon());
            for (int k = 1; k <= 3; k++) {
                pings.add(new Ping(anchor.time().plusMillis(400L * k), farAway, 5)); // 9 km off, a second apart
            }
        }
        if (rnd.nextDouble() < 0.05) {
            pings.add(new Ping(start.minusSeconds(120), route.at(0), 5));
            pings.add(new Ping(start.minusSeconds(60), route.at(0), 5));
        }
        Collections.shuffle(pings, rnd);

        List<EmployeeStop> stops = new ArrayList<>();
        for (int i = 0; i < route.stopDistances().length; i++) {
            stops.add(stop(route, i, durationSeconds, start, index, rnd));
        }

        TripEvidence evidence = new TripEvidence(TripType.LOGOUT, start, end, null, null, route.at(0), pings, stops);
        return new SyntheticTrip(evidence, route.id(), route.lengthMeters() / 1000.0, burst);
    }

    private static EmployeeStop stop(Route route, int i, int durationSeconds, Instant start, int tripIndex,
                                     Random rnd) {
        double meters = route.stopDistances()[i];
        GeoPoint place = route.at(meters);
        Instant time = start.plusMillis(Math.round(1000.0 * durationSeconds * meters / route.lengthMeters()));
        String ref = "E" + tripIndex + "-" + i;
        double kind = rnd.nextDouble();
        if (kind < 0.7) {
            // the driver marked the drop: the normal case
            return new EmployeeStop(ref, RiderType.PLANNED, StopEvent.DROP, place, time, null, null, null, null);
        }
        if (kind < 0.85) {
            // no drop recorded: fall back to the roster
            return new EmployeeStop(ref, RiderType.PLANNED, StopEvent.DROP, null, null, place, time, null, null);
        }
        // an ad-hoc rider: fall back to the home address and sign-in time
        return new EmployeeStop(ref, RiderType.AD_HOC, StopEvent.DROP, null, null, null, null, place, time);
    }

    /** A couple of metres of GPS wobble in each direction. */
    private static GeoPoint jitter(GeoPoint p, Random rnd) {
        double dLat = (rnd.nextDouble() - 0.5) * 4 / METERS_PER_DEGREE;
        double dLon = (rnd.nextDouble() - 0.5) * 4 / (METERS_PER_DEGREE * Math.cos(Math.toRadians(p.lat())));
        return new GeoPoint(p.lat() + dLat, p.lon() + dLon);
    }
}

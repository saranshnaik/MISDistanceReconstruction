package com.moveinsync.distance.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds trip request bodies for the API tests. */
final class TripJson {

    private static final Instant START = Instant.parse("2026-03-01T08:00:00Z");
    private static final double METERS_PER_DEGREE = 111_195.08;

    private TripJson() {
    }

    static Map<String, Object> point(double lat, double lon) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("lat", lat);
        p.put("lon", lon);
        return p;
    }

    static String isoAt(int secondsAfterStart) {
        return START.plusSeconds(secondsAfterStart).toString();
    }

    /**
     * A cab driving north with a 22-minute hole in the pings and one drop inside it, so routing
     * is needed. Each ref gets its own stretch of map so cached routes never leak between tests.
     */
    static Map<String, Object> underpass(String ref, String droppedEmployeeRef) {
        double originLat = 12.9352;
        double originLon = 77.6245 + (Math.abs(ref.hashCode()) % 20_000) * 0.002;
        List<Map<String, Object>> pings = new ArrayList<>();
        for (int s = 0; s <= 3000; s += 10) {
            if (s > 600 && s < 1920) {
                continue;
            }
            Map<String, Object> p = point(originLat + s * 10.0 / METERS_PER_DEGREE, originLon);
            p.put("time", isoAt(s));
            p.put("accuracyM", 5);
            pings.add(p);
        }
        Map<String, Object> drop = new LinkedHashMap<>();
        drop.put("employeeRef", droppedEmployeeRef);
        drop.put("riderType", "PLANNED");
        drop.put("event", "DROP");
        drop.put("actual", point(originLat + 9_000 / METERS_PER_DEGREE, originLon));
        drop.put("actualTime", isoAt(900));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tripRef", ref);
        body.put("type", "LOGOUT");
        body.put("tripStart", isoAt(0));
        body.put("tripEnd", isoAt(3000));
        body.put("office", point(originLat, originLon));
        body.put("pings", pings);
        body.put("stops", List.of(drop));
        return body;
    }
}

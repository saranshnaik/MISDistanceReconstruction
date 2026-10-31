package com.moveinsync.distance.service;

import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.Ping;
import com.moveinsync.distance.engine.model.TripEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Fingerprint of everything the engine reads. If it and the rule version match the last
 * computation, running the engine again could only give the same answer, so we skip it.
 * Pings and stops are sorted first so arrival order doesn't change the hash.
 */
final class InputHasher {

    private static final Comparator<Ping> PING_ORDER = Comparator.comparing(Ping::time)
            .thenComparingDouble(p -> p.point().lat())
            .thenComparingDouble(p -> p.point().lon())
            .thenComparingDouble(Ping::accuracyM);

    private static final Comparator<EmployeeStop> STOP_ORDER = Comparator.comparing(EmployeeStop::employeeRef)
            .thenComparing(EmployeeStop::event);

    private InputHasher() {
    }

    static String hash(TripEvidence trip) {
        StringBuilder text = new StringBuilder();
        text.append(trip.type()).append('|').append(trip.tripStart()).append('|').append(trip.tripEnd()).append('|')
                .append(point(trip.actualStartPoint())).append('|').append(point(trip.actualEndPoint())).append('|')
                .append(point(trip.office())).append('\n');

        List<Ping> pings = trip.pings().stream().sorted(PING_ORDER).toList();
        for (Ping ping : pings) {
            text.append(ping.time()).append(',').append(point(ping.point())).append(',')
                    .append(ping.accuracyM()).append('\n');
        }
        List<EmployeeStop> stops = trip.stops().stream().sorted(STOP_ORDER).toList();
        for (EmployeeStop s : stops) {
            text.append(s.employeeRef()).append(',').append(s.riderType()).append(',').append(s.event()).append(',')
                    .append(point(s.actualPoint())).append(',').append(s.actualTime()).append(',')
                    .append(point(s.plannedPoint())).append(',').append(s.plannedTime()).append(',')
                    .append(point(s.homePoint())).append(',').append(s.signInTime()).append('\n');
        }
        return sha256(text.toString());
    }

    private static String point(GeoPoint p) {
        return p == null ? "-" : p.lat() + ":" + p.lon();
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}

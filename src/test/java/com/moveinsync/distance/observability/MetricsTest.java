package com.moveinsync.distance.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.distance.engine.routing.SimulatedRoutingProvider;
import com.moveinsync.distance.web.TripJson;
import com.moveinsync.distance.service.DegradedTripRecomputeJob;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
class MetricsTest {

    private static final double METERS_PER_DEGREE = 111_195.08;

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;
    @Autowired
    MeterRegistry registry;
    @Autowired
    SimulatedRoutingProvider provider;
    @Autowired
    DegradedTripRecomputeJob job;

    @AfterEach
    void providerBackUp() {
        provider.setDown(false);
    }

    private String bearer(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"demo-pass\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asString();
    }

    private void submit(Map<String, Object> body) throws Exception {
        mvc.perform(post("/api/trips").header("Authorization", bearer("acme-operator"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
        ).andExpect(status().isOk());
    }

    private double counter(String name, String... tags) {
        return registry.find(name).tags(tags).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double computeCount() {
        return registry.find("distance.compute").timers().stream().mapToLong(Timer::count).sum();
    }

    private static String newRef() {
        return "T-" + UUID.randomUUID();
    }

    @Test
    void computingATripCountsSegmentsByRuleRoutingAndTime() throws Exception {
        double routedBefore = counter("distance.segments", "rule", "ROUTED");
        double shortBefore = counter("distance.segments", "rule", "SHORT_GAP_AERIAL");
        double requestsBefore = counter("distance.routing.requests");
        double timerBefore = computeCount();

        submit(TripJson.underpass(newRef(), "E1"));

        assertEquals(routedBefore + 2, counter("distance.segments", "rule", "ROUTED"));
        assertTrue(counter("distance.segments", "rule", "SHORT_GAP_AERIAL") > shortBefore);
        assertEquals(requestsBefore + 2, counter("distance.routing.requests"));
        assertEquals(timerBefore + 1, computeCount());
    }

    @Test
    void droppedPingsAreCountedByReason() throws Exception {
        double duplicateBefore = counter("distance.pings.dropped", "reason", "duplicate");
        double accuracyBefore = counter("distance.pings.dropped", "reason", "low_accuracy");
        double teleportBefore = counter("distance.pings.dropped", "reason", "teleport");
        String ref = newRef();
        Map<String, Object> body = TripJson.underpass(ref, "E1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pings = (List<Map<String, Object>>) body.get("pings");
        Map<String, Object> first = pings.get(30);
        double lat = (Double) first.get("lat");
        double lon = (Double) first.get("lon");
        pings.add(ping(TripJson.isoAt(300), lat + 0.00001, lon, 5));    // same second as another ping: duplicate
        pings.add(ping(TripJson.isoAt(305), lat, lon, 500));            // 500 m accuracy: low accuracy
        pings.add(ping(TripJson.isoAt(311), lat + 0.5, lon, 5));        // 55 km away one second later: teleport

        submit(body);

        assertTrue(counter("distance.pings.dropped", "reason", "duplicate") >= duplicateBefore + 1);
        assertTrue(counter("distance.pings.dropped", "reason", "low_accuracy") >= accuracyBefore + 1);
        assertTrue(counter("distance.pings.dropped", "reason", "teleport") >= teleportBefore + 1);
    }

    private static Map<String, Object> ping(String time, double lat, double lon, double accuracy) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("time", time);
        p.put("lat", lat);
        p.put("lon", lon);
        p.put("accuracyM", accuracy);
        return p;
    }

    @Test
    void closingAnInvoiceAndLatePingsAreCounted() throws Exception {
        double closedBefore = counter("distance.billing.closed");
        double lateBefore = counter("distance.pings.late");
        double negativeBefore = counter("distance.billing.adjustments", "direction", "negative");
        long lagSamplesBefore = registry.find("distance.ping.lag.seconds").summaries().stream()
                .mapToLong(s -> s.count()).sum();
        String ref = newRef();
        submit(TripJson.underpass(ref, "E1"));
        mvc.perform(post("/api/trips/" + ref + "/bill").header("Authorization", bearer("acme-admin")))
                .andExpect(status().isOk());

        // pings that fill the 22-minute hole: the routed (inflated) gap becomes real driving
        double originLon = 77.6245 + (Math.abs(ref.hashCode()) % 20_000) * 0.002;
        List<Map<String, Object>> filler = new ArrayList<>();
        for (int s = 610; s <= 1910; s += 10) {
            filler.add(ping(TripJson.isoAt(s), 12.9352 + s * 10.0 / METERS_PER_DEGREE, originLon, 5));
        }
        mvc.perform(post("/api/trips/" + ref + "/pings").header("Authorization", bearer("acme-operator"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("pings", filler))))
                .andExpect(status().isOk());

        assertEquals(closedBefore + 1, counter("distance.billing.closed"));
        assertEquals(lateBefore + filler.size(), counter("distance.pings.late"));
        assertEquals(negativeBefore + 1, counter("distance.billing.adjustments", "direction", "negative"));
        long lagSamplesAfter = registry.find("distance.ping.lag.seconds").summaries().stream()
                .mapToLong(s -> s.count()).sum();
        assertEquals(lagSamplesBefore + filler.size(), lagSamplesAfter);
    }

    @Test
    void anOutageShowsUpAsABacklogWithAnAgeAndRecoveryDrainsIt() throws Exception {
        double failuresBefore = counter("distance.routing.failures");
        double backlogBefore = gauge("distance.trips.degraded");
        double recoveredBefore = counter("distance.recompute", "outcome", "recovered");

        provider.setDown(true);
        submit(TripJson.underpass(newRef(), "E1"));
        provider.setDown(false);

        assertEquals(failuresBefore + 1, counter("distance.routing.failures"));
        assertTrue(gauge("distance.trips.degraded") >= backlogBefore + 1, "backlog did not grow");
        assertTrue(gauge("distance.trips.degraded.oldest.age.seconds") >= 0);
        double backlogDuringOutage = gauge("distance.trips.degraded");

        job.runOnce();

        assertTrue(gauge("distance.trips.degraded") < backlogDuringOutage, "backlog did not shrink");
        assertTrue(counter("distance.recompute", "outcome", "recovered") >= recoveredBefore + 1);
    }

    @Test
    void theCircuitStateIsAGaugeAndOpeningsAreCounted() {
        assertTrue(gauge("distance.routing.circuit.state") >= 0);
        assertTrue(registry.find("distance.routing.circuit.opened").functionCounter() != null);
        assertTrue(gauge("distance.routing.cache.size") >= 0);
    }

    @Test
    void thePrometheusEndpointIsForAdminsOnlyAndLeaksNoTripOrTenantNames() throws Exception {
        String ref = newRef();
        submit(TripJson.underpass(ref, "E1"));

        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", bearer("acme-operator")))
                .andExpect(status().isForbidden());
        String text = mvc.perform(get("/actuator/prometheus").header("Authorization", bearer("acme-admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        for (String expected : List.of("distance_segments_total", "distance_routing_calls_total",
                "distance_trips_degraded", "distance_trips_degraded_oldest_age_seconds",
                "distance_routing_circuit_state", "distance_compute_seconds_count",
                "http_server_requests_seconds_count")) {
            assertTrue(text.contains(expected), "missing metric " + expected);
        }
        assertFalse(text.contains(ref), "a trip ref became a metric label");
        assertFalse(text.contains("acme"), "a tenant name became a metric label");
        assertFalse(text.contains("alice@"), "personal data in metrics");
    }

    @Test
    void everyMetricTagComesFromAFixedList() {
        List<String> allowedTagKeys = List.of("status", "rule", "reason", "outcome", "direction",
                "method", "uri", "exception", "error", "outcome", "area", "id", "name", "application",
                "cause", "result", "state", "action", "region", "type", "class", "quantile", "le");
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (!name.startsWith("distance.")) {
                continue;
            }
            meter.getId().getTags().forEach(tag -> assertTrue(allowedTagKeys.contains(tag.getKey()),
                    name + " has unexpected tag " + tag.getKey()));
        }
    }
}

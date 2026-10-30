package com.moveinsync.distance.web;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class TripControllerTest {

    private static final double METERS_PER_DEGREE = 111_195.08;

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;

    private static Map<String, Object> point(double lat, double lon) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("lat", lat);
        p.put("lon", lon);
        return p;
    }

    private static String isoAt(int secondsAfterStart) {
        return Instant.parse("2026-03-01T08:00:00Z").plusSeconds(secondsAfterStart).toString();
    }

    /** A request for a trip driving north along a stretch of map that belongs only to this ref. */
    private static Map<String, Object> validRequest(String ref) {
        double originLat = 12.9352;
        double originLon = 77.6245 + (Math.abs(ref.hashCode()) % 20_000) * 0.002;
        List<Map<String, Object>> pings = new ArrayList<>();
        for (int s = 0; s <= 600; s += 10) {
            pings.add(ping(s, originLat + s * 10.0 / METERS_PER_DEGREE, originLon));
        }
        for (int s = 1920; s <= 3000; s += 10) {
            pings.add(ping(s, originLat + s * 10.0 / METERS_PER_DEGREE, originLon));
        }
        Map<String, Object> drop = new LinkedHashMap<>();
        drop.put("employeeRef", "E1");
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

    private static Map<String, Object> ping(int second, double lat, double lon) {
        Map<String, Object> p = point(lat, lon);
        p.put("time", isoAt(second));
        p.put("accuracyM", 5);
        return p;
    }

    private String token;

    /** Logs in as the demo operator of tenant "acme" (once per test). */
    private String bearer() throws Exception {
        if (token == null) {
            String response = mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"acme-operator\",\"password\":\"demo-pass\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            token = json.readTree(response).get("accessToken").asString();
        }
        return "Bearer " + token;
    }

    private ResultActions submit(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/trips").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private static String newRef() {
        return "T-" + UUID.randomUUID();
    }

    @Test
    void submittingATripReturnsItsDistanceAndState() throws Exception {
        submit(validRequest(newRef()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceState").value("OK"))
                .andExpect(jsonPath("$.billingState").value("UNBILLED"))
                .andExpect(jsonPath("$.billableKm", greaterThan(0.0)))
                .andExpect(jsonPath("$.routeRequests").value(2))
                .andExpect(jsonPath("$.ruleVersion", containsString("v1-")));
    }

    @Test
    void submittingTheSameTripTwiceReturnsTheSameComputation() throws Exception {
        String ref = newRef();
        String first = submit(validRequest(ref)).andReturn().getResponse().getContentAsString();
        String second = submit(validRequest(ref)).andReturn().getResponse().getContentAsString();

        assertEquals(
                json.readTree(first).get("computationId"), json.readTree(second).get("computationId"));
    }

    @Test
    void theTripCanBeReadBackByRef() throws Exception {
        String ref = newRef();
        submit(validRequest(ref)).andExpect(status().isOk());

        mvc.perform(get("/api/trips/" + ref).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripRef").value(ref))
                .andExpect(jsonPath("$.tripType").value("LOGOUT"));
    }

    @Test
    void segmentsExplainEveryKilometreWithItsRule() throws Exception {
        String ref = newRef();
        submit(validRequest(ref)).andExpect(status().isOk());

        mvc.perform(get("/api/trips/" + ref + "/segments").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].rule", hasItem("ROUTED")))
                .andExpect(jsonPath("$[*].rule", hasItem("SHORT_GAP_AERIAL")))
                .andExpect(jsonPath("$[0].billedMm").exists());
    }

    @Test
    void recomputeOfAnUnchangedTripKeepsTheSameComputation() throws Exception {
        String ref = newRef();
        String first = submit(validRequest(ref)).andReturn().getResponse().getContentAsString();

        String again = mvc.perform(post("/api/trips/" + ref + "/recompute").header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(
                json.readTree(first).get("computationId"), json.readTree(again).get("computationId"));
    }

    @Test
    void unknownTripIsA404WithNothingInTheBody() throws Exception {
        mvc.perform(get("/api/trips/does-not-exist").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("does-not-exist"))));
        mvc.perform(get("/api/trips/does-not-exist/segments").header("Authorization", bearer())).andExpect(status().isNotFound());
        mvc.perform(post("/api/trips/does-not-exist/recompute").header("Authorization", bearer())).andExpect(status().isNotFound());
    }

    @Test
    void blankTripRefIsRejected() throws Exception {
        Map<String, Object> body = validRequest(newRef());
        body.put("tripRef", " ");

        submit(body).andExpect(status().isBadRequest());
    }

    @Test
    void tripEndingBeforeItStartsIsRejected() throws Exception {
        Map<String, Object> body = validRequest(newRef());
        body.put("tripEnd", isoAt(-60));

        submit(body).andExpect(status().isBadRequest());
    }

    @Test
    void missingOfficeIsRejected() throws Exception {
        Map<String, Object> body = validRequest(newRef());
        body.remove("office");

        submit(body).andExpect(status().isBadRequest());
    }

    @Test
    void officeAtNullIslandIsRejected() throws Exception {
        Map<String, Object> body = validRequest(newRef());
        body.put("office", point(0, 0));

        submit(body).andExpect(status().isBadRequest());
    }

    @Test
    void aPingWithAnImpossibleLatitudeDoesNotSinkTheWholeTrip() throws Exception {
        Map<String, Object> body = validRequest(newRef());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pings = (List<Map<String, Object>>) body.get("pings");
        pings.add(ping(305, 95.0, 77.6)); // the engine drops it as an invalid coordinate

        submit(body).andExpect(status().isOk()).andExpect(jsonPath("$.distanceState").value("OK"));
    }

    @Test
    void malformedJsonIsA400() throws Exception {
        mvc.perform(post("/api/trips").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }
}

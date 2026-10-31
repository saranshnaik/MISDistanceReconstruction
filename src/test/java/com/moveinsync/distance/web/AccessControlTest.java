package com.moveinsync.distance.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.moveinsync.distance.engine.routing.SimulatedRoutingProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may do what. All accounts use password demo-pass: acme-employee (employee E1),
 * acme-operator, acme-admin, and the same for globex.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessControlTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;
    @Autowired
    SimulatedRoutingProvider provider;

    @AfterEach
    void providerBackUp() {
        provider.setDown(false);
    }

    private String tokenFor(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"demo-pass\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asString();
    }

    private int call(String username, HttpMethod method, String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = method == HttpMethod.GET ? get(path) : post(path);
        if (username != null) {
            request = request.header("Authorization", tokenFor(username));
        }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static String newRef() {
        return "T-" + UUID.randomUUID();
    }

    /** acme-operator submits a trip that acme-employee (E1) was dropped on. */
    private String submitAcmeTrip() throws Exception {
        String ref = newRef();
        assertEquals(200, call("acme-operator", HttpMethod.POST, "/api/trips", TripJson.underpass(ref, "E1")));
        return ref;
    }

    private static final Map<String, Object> ONE_LATE_PING = Map.of("pings", List.of(Map.of(
            "time", TripJson.isoAt(2999), "lat", 12.99, "lon", 77.62, "accuracyM", 5)));

    @Test
    void anEmployeeCanOnlyReadTheirOwnTripSummary() throws Exception {
        String ref = submitAcmeTrip();

        assertEquals(200, call("acme-employee", HttpMethod.GET, "/api/trips/" + ref, null));
        assertEquals(403, call("acme-employee", HttpMethod.GET, "/api/trips/" + ref + "/segments", null));
        assertEquals(403, call("acme-employee", HttpMethod.POST, "/api/trips/" + ref + "/recompute", null));
        assertEquals(403, call("acme-employee", HttpMethod.POST, "/api/trips/" + ref + "/pings", ONE_LATE_PING));
        assertEquals(403, call("acme-employee", HttpMethod.POST, "/api/trips/" + ref + "/bill", null));
        assertEquals(403, call("acme-employee", HttpMethod.GET, "/api/trips/" + ref + "/adjustments", null));
        assertEquals(403, call("acme-employee", HttpMethod.POST, "/api/trips", TripJson.underpass(newRef(), "E1")));
        assertEquals(403, call("acme-employee", HttpMethod.GET, "/api/admin/routing", null));
    }

    @Test
    void anEmployeeCannotSeeATripTheyWereNotOn() throws Exception {
        String ref = newRef();
        assertEquals(200, call("acme-operator", HttpMethod.POST, "/api/trips", TripJson.underpass(ref, "E99")));

        assertEquals(404, call("acme-employee", HttpMethod.GET, "/api/trips/" + ref, null));
    }

    @Test
    void anOperatorRunsTripsButCannotTouchMoneyOrSystemControls() throws Exception {
        String ref = submitAcmeTrip();

        assertEquals(200, call("acme-operator", HttpMethod.GET, "/api/trips/" + ref, null));
        assertEquals(200, call("acme-operator", HttpMethod.GET, "/api/trips/" + ref + "/segments", null));
        assertEquals(200, call("acme-operator", HttpMethod.POST, "/api/trips/" + ref + "/recompute", null));
        assertEquals(200, call("acme-operator", HttpMethod.POST, "/api/trips/" + ref + "/pings", ONE_LATE_PING));
        assertEquals(403, call("acme-operator", HttpMethod.POST, "/api/trips/" + ref + "/bill", null));
        assertEquals(403, call("acme-operator", HttpMethod.GET, "/api/trips/" + ref + "/adjustments", null));
        assertEquals(403, call("acme-operator", HttpMethod.GET, "/api/admin/routing", null));
        assertEquals(403, call("acme-operator", HttpMethod.POST, "/api/admin/routing/outage", Map.of("down", true)));
    }

    @Test
    void anAdminCanDoEverythingWithinTheirOwnTenant() throws Exception {
        String ref = submitAcmeTrip();

        assertEquals(200, call("acme-admin", HttpMethod.GET, "/api/trips/" + ref, null));
        assertEquals(200, call("acme-admin", HttpMethod.GET, "/api/trips/" + ref + "/segments", null));
        assertEquals(200, call("acme-admin", HttpMethod.POST, "/api/trips/" + ref + "/bill", null));
        assertEquals(200, call("acme-admin", HttpMethod.GET, "/api/trips/" + ref + "/adjustments", null));
        assertEquals(200, call("acme-admin", HttpMethod.GET, "/api/admin/routing", null));
    }

    @Test
    void noTokenIsAlways401() throws Exception {
        assertEquals(401, call(null, HttpMethod.GET, "/api/trips/x", null));
        assertEquals(401, call(null, HttpMethod.POST, "/api/trips", TripJson.underpass(newRef(), "E1")));
        assertEquals(401, call(null, HttpMethod.POST, "/api/admin/routing/outage", Map.of("down", true)));
    }

    @Test
    void anythingNotExplicitlyAllowedIsClosedEvenForAnAdmin() throws Exception {
        assertEquals(403, call("acme-admin", HttpMethod.GET, "/api/not-a-real-endpoint", null));
        assertEquals(403, call("acme-admin", HttpMethod.GET, "/actuator/env", null));
        assertEquals(403, call("acme-admin", HttpMethod.POST, "/api/trips/x/delete", null));
    }

    @Test
    void anotherTenantSeesNothingOnEveryEndpointNotEvenAForbiddenOrNotFoundHint() throws Exception {
        String ref = submitAcmeTrip();

        for (String user : List.of("globex-employee", "globex-operator", "globex-admin")) {
            assertEquals(404, call(user, HttpMethod.GET, "/api/trips/" + ref, null), user + " GET trip");
        }
        assertEquals(404, call("globex-operator", HttpMethod.GET, "/api/trips/" + ref + "/segments", null));
        assertEquals(404, call("globex-operator", HttpMethod.POST, "/api/trips/" + ref + "/recompute", null));
        assertEquals(404, call("globex-operator", HttpMethod.POST, "/api/trips/" + ref + "/pings", ONE_LATE_PING));
        assertEquals(404, call("globex-admin", HttpMethod.POST, "/api/trips/" + ref + "/bill", null));
        assertEquals(404, call("globex-admin", HttpMethod.GET, "/api/trips/" + ref + "/adjustments", null));
    }

    @Test
    void theOtherTenantsAttemptsLeaveTheTripUntouched() throws Exception {
        String ref = submitAcmeTrip();
        call("globex-admin", HttpMethod.POST, "/api/trips/" + ref + "/bill", null);
        call("globex-operator", HttpMethod.POST, "/api/trips/" + ref + "/pings", ONE_LATE_PING);

        String body = mvc.perform(get("/api/trips/" + ref).header("Authorization", tokenFor("acme-admin")))
                .andReturn().getResponse().getContentAsString();
        assertEquals("UNBILLED", json.readTree(body).get("billingState").asString());
    }

    @Test
    void theSameTripRefInTwoTenantsAreTwoSeparateTrips() throws Exception {
        String ref = newRef();
        call("acme-operator", HttpMethod.POST, "/api/trips", TripJson.underpass(ref, "E1"));
        call("globex-operator", HttpMethod.POST, "/api/trips", TripJson.underpass(ref, "E1"));

        String acme = mvc.perform(get("/api/trips/" + ref).header("Authorization", tokenFor("acme-operator")))
                .andReturn().getResponse().getContentAsString();
        String globex = mvc.perform(get("/api/trips/" + ref).header("Authorization", tokenFor("globex-operator")))
                .andReturn().getResponse().getContentAsString();

        assertNotEquals(json.readTree(acme).get("computationId"), json.readTree(globex).get("computationId"));
    }

    @Test
    void anAdminCanStageAnOutageAndRecoverFromIt() throws Exception {
        String ref = newRef();

        assertEquals(200, call("acme-admin", HttpMethod.POST, "/api/admin/routing/outage", Map.of("down", true)));
        String degraded = mvc.perform(post("/api/trips").header("Authorization", tokenFor("acme-operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(TripJson.underpass(ref, "E1"))))
                .andReturn().getResponse().getContentAsString();
        assertEquals("DEGRADED", json.readTree(degraded).get("distanceState").asString());
        assertEquals(409, call("acme-admin", HttpMethod.POST, "/api/trips/" + ref + "/bill", null)); // not billable yet

        assertEquals(200, call("acme-admin", HttpMethod.POST, "/api/admin/routing/outage", Map.of("down", false)));
        String recovered = mvc.perform(post("/api/trips/" + ref + "/recompute")
                        .header("Authorization", tokenFor("acme-operator")))
                .andReturn().getResponse().getContentAsString();
        assertEquals("OK", json.readTree(recovered).get("distanceState").asString());
        assertEquals(200, call("acme-admin", HttpMethod.POST, "/api/trips/" + ref + "/bill", null));
    }
}

package com.moveinsync.distance.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Every kind of failure has the same shape, a stable code, and nothing the caller sent. */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorResponsesTest {

    private static final String SECRET_REF = "secret-trip-ref-4711";
    private static final String SECRET_EMAIL = "alice@example.com";

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;

    private String bearer(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"demo-pass\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asString();
    }

    @Test
    void unknownTripIsA404WithACodeAndNoEchoOfTheRef() throws Exception {
        mvc.perform(get("/api/trips/" + SECRET_REF).header("Authorization", bearer("acme-operator")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("trip not found"))
                .andExpect(content().string(not(containsString(SECRET_REF))));
    }

    @Test
    void billingATripThatIsNotReadyIsA409WithACode() throws Exception {
        // an estimated trip: stage an outage so the new trip is DEGRADED
        mvc.perform(post("/api/admin/routing/outage").header("Authorization", bearer("acme-admin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"down\":true}")).andExpect(status().isOk());
        String ref = "T-" + UUID.randomUUID();
        try {
            mvc.perform(post("/api/trips").header("Authorization", bearer("acme-operator"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(TripJson.underpass(ref, "E1")))).andExpect(status().isOk());

            mvc.perform(post("/api/trips/" + ref + "/bill").header("Authorization", bearer("acme-admin")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TRIP_NOT_BILLABLE"));
        } finally {
            mvc.perform(post("/api/admin/routing/outage").header("Authorization", bearer("acme-admin"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"down\":false}"));
        }
    }

    @Test
    void validationErrorsNameTheFieldAndTheRuleButNeverTheRejectedValue() throws Exception {
        Map<String, Object> body = TripJson.underpass("T-" + UUID.randomUUID(), SECRET_EMAIL);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) body.get("stops");
        stops.get(0).remove("riderType"); // invalid, while employeeRef carries a personal value

        mvc.perform(post("/api/trips").header("Authorization", bearer("acme-operator"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("stops[0].riderType")))
                .andExpect(content().string(not(containsString(SECRET_EMAIL))));
    }

    @Test
    void validationAlsoCoversCrossFieldRules() throws Exception {
        Map<String, Object> body = TripJson.underpass("T-" + UUID.randomUUID(), "E1");
        body.put("tripEnd", TripJson.isoAt(-60));

        mvc.perform(post("/api/trips").header("Authorization", bearer("acme-operator"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].rule", hasItem("tripEnd must be after tripStart")));
    }

    @Test
    void malformedJsonIsA400AndTheParserMessageIsNotPassedOn() throws Exception {
        mvc.perform(post("/api/trips").header("Authorization", bearer("acme-operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripRef\": \"" + SECRET_REF + "\", \"type\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("request body could not be read"))
                .andExpect(content().string(not(containsString(SECRET_REF))));
    }

    @Test
    void wrongMethodGetsTheSameShape() throws Exception {
        mvc.perform(get("/api/auth/token").header("Authorization", bearer("acme-operator")))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    void noTokenIs401WithTheSameShapeAndNoReasonGiven() throws Exception {
        mvc.perform(get("/api/trips/x"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anExpiredOrBrokenTokenGetsTheSameAnswerAsNoToken() throws Exception {
        mvc.perform(get("/api/trips/x").header("Authorization", "Bearer broken.token.value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value("authentication required"));
    }

    @Test
    void wrongRoleIs403WithTheSameShape() throws Exception {
        mvc.perform(post("/api/trips/x/bill").header("Authorization", bearer("acme-employee")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void badLoginIsA401WithACode() throws Exception {
        mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"acme-admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}

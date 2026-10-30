package com.moveinsync.distance.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RequestTraceFilterTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;

    private String bearer() throws Exception {
        String body = mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"acme-operator\",\"password\":\"demo-pass\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asString();
    }

    @Test
    void everyResponseCarriesATraceId() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn();

        String id = result.getResponse().getHeader("X-Request-Id");
        assertTrue(id != null && id.length() >= 16, "id was " + id);
    }

    @Test
    void aSaneIdFromTheCallerIsKept() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/health").header("X-Request-Id", "client-req-12345")).andReturn();

        assertEquals("client-req-12345", result.getResponse().getHeader("X-Request-Id"));
    }

    @Test
    void aSuspiciousIdIsReplacedSoItCannotForgeLogLinesOrHeaders() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/health")
                .header("X-Request-Id", "abc def\" injected=true \n fake log line")).andReturn();

        String id = result.getResponse().getHeader("X-Request-Id");
        assertNotEquals("abc def\" injected=true \n fake log line", id);
        assertTrue(id.matches("[A-Za-z0-9]{32}"), "id was " + id);
    }

    @Test
    void differentRequestsGetDifferentIds() throws Exception {
        String first = mvc.perform(get("/actuator/health")).andReturn().getResponse().getHeader("X-Request-Id");
        String second = mvc.perform(get("/actuator/health")).andReturn().getResponse().getHeader("X-Request-Id");

        assertNotEquals(first, second);
    }

    @Test
    void errorBodiesCarryTheSameIdAsTheHeader() throws Exception {
        MvcResult notFound = mvc.perform(get("/api/trips/nope").header("Authorization", bearer())
                .header("X-Request-Id", "trace-for-404-abc")).andReturn();
        MvcResult unauthorized = mvc.perform(get("/api/trips/nope")
                .header("X-Request-Id", "trace-for-401-abc")).andReturn();

        assertEquals("trace-for-404-abc", json.readTree(notFound.getResponse().getContentAsString()).get("traceId").asString());
        assertEquals("trace-for-401-abc", json.readTree(unauthorized.getResponse().getContentAsString()).get("traceId").asString());
    }

    @Test
    void theIdDoesNotLeakToTheNextRequestOnTheSameThread() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Request-Id", "will-be-cleared-1"));

        assertNull(MDC.get("traceId"));
    }

    @Test
    void theSummaryLineHasTheTraceIdAndTheRoutePatternButNotTheTripRef(CapturedOutput log) throws Exception {
        mvc.perform(get("/api/trips/secret-ref-998877").header("Authorization", bearer())
                .header("X-Request-Id", "trace-in-log-1234"));

        String all = log.getAll();
        assertTrue(all.contains("trace-in-log-1234"), "trace id missing from the log");
        assertTrue(all.contains("route=/api/trips/{tripRef}"), "route pattern missing from the log");
        assertFalse(all.contains("secret-ref-998877"), "the real trip ref reached the log");
    }

    @Test
    void logsAreOneJsonObjectPerLineWithTheTraceIdAsAField(CapturedOutput log) throws Exception {
        mvc.perform(get("/actuator/health").header("X-Request-Id", "json-check-trace-1"));

        boolean found = log.getAll().lines()
                .anyMatch(line -> line.startsWith("{") && line.contains("\"traceId\":\"json-check-trace-1\""));
        assertTrue(found, "no JSON log line with the trace id");
    }
}

package com.moveinsync.distance.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.distance.service.TripService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** What happens when something we didn't plan for goes wrong deep inside. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class UnexpectedErrorTest {

    private static final String LEAKY_MESSAGE =
            "insert failed for alice@example.com home 12.93521,77.62451 trip secret-trip-ref-4711";

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;

    @MockitoBean
    TripService tripService;

    private String bearer() throws Exception {
        String body = mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"acme-operator\",\"password\":\"demo-pass\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("accessToken").asString();
    }

    @Test
    void anUnexpectedFailureIsA500WithNothingFromTheRealCause(CapturedOutput log) throws Exception {
        when(tripService.get(any(), any())).thenThrow(new IllegalStateException(LEAKY_MESSAGE));

        mvc.perform(get("/api/trips/whatever").header("Authorization", bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("unexpected error"))
                .andExpect(content().string(not(containsString("alice@example.com"))))
                .andExpect(content().string(not(containsString("12.93521"))))
                .andExpect(content().string(not(containsString("secret-trip-ref-4711"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));

        // the server log says WHAT broke and WHERE, but not what was inside the message
        assertFalse(log.getAll().contains("alice@example.com"), "personal data reached the log");
        assertFalse(log.getAll().contains("12.93521"), "a coordinate reached the log");
        assertFalse(log.getAll().contains("secret-trip-ref-4711"), "a trip ref reached the log");
        org.junit.jupiter.api.Assertions.assertTrue(log.getAll().contains("IllegalStateException"));
    }
}

package com.moveinsync.distance.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.distance.persistence.AppUserEntity;
import com.moveinsync.distance.persistence.AppUserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;
    @Autowired
    JwtDecoder jwtDecoder;
    @Autowired
    AppUserRepository users;
    @Autowired
    PasswordEncoder passwordEncoder;

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    private String tokenFor(String username) throws Exception {
        String body = login(username, "demo-pass").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asString();
    }

    private String withoutTraceId(String problemJson) {
        return json.readTree(problemJson).propertyNames().stream()
                .filter(name -> !name.equals("traceId"))
                .map(name -> name + "=" + json.readTree(problemJson).get(name))
                .sorted().toList().toString();
    }

    private ResultActions callProtected(String authorizationHeader) throws Exception {
        var request = get("/api/trips/anything");
        if (authorizationHeader != null) {
            request = request.header("Authorization", authorizationHeader);
        }
        return mvc.perform(request);
    }

    // ---- logging in ----

    @Test
    void correctPasswordGivesABearerTokenAndTheRole() throws Exception {
        login("acme-admin", "demo-pass")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.expiresInSeconds").value(3600))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() throws Exception {
        String wrongPassword = login("acme-admin", "nope").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownUser = login("nobody-here", "nope").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // identical apart from the per-request trace id: nothing reveals which usernames exist
        assertEquals(withoutTraceId(wrongPassword), withoutTraceId(unknownUser));
    }

    @Test
    void blankCredentialsAreRejected() throws Exception {
        login("", "").andExpect(status().isBadRequest());
    }

    @Test
    void passwordsAreStoredOnlyAsBcryptHashes() {
        AppUserEntity user = users.findByUsername("acme-admin").orElseThrow();

        assertNotEquals("demo-pass", user.getPasswordHash());
        assertTrue(user.getPasswordHash().startsWith("$2"));
        assertTrue(passwordEncoder.matches("demo-pass", user.getPasswordHash()));
    }

    @Test
    void theTokenCarriesTenantAndRoleFromTheAccountNotFromTheRequest() throws Exception {
        Jwt acme = jwtDecoder.decode(tokenFor("acme-operator"));
        Jwt globex = jwtDecoder.decode(tokenFor("globex-employee"));

        assertEquals("acme", acme.getClaimAsString("tenant"));
        assertEquals("OPERATOR", acme.getClaimAsString("role"));
        assertEquals("globex", globex.getClaimAsString("tenant"));
        assertEquals("EMPLOYEE", globex.getClaimAsString("role"));
        assertEquals("E1", globex.getClaimAsString("employeeRef"));
    }

    // ---- using it ----

    @Test
    void noTokenMeansUnauthorized() throws Exception {
        callProtected(null).andExpect(status().isUnauthorized());
    }

    @Test
    void garbageTokenMeansUnauthorized() throws Exception {
        callProtected("Bearer not-a-token").andExpect(status().isUnauthorized());
        callProtected("Basic YWNtZTpkZW1v").andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenGetsPastSecurity() throws Exception {
        // the trip does not exist, so the answer is a 404 from the app, not a 401 from security
        callProtected("Bearer " + tokenFor("acme-operator")).andExpect(status().isNotFound());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String[] parts = tokenFor("acme-employee").split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"iss\":\"distance-engine\",\"sub\":\"acme-employee\",\"tenant\":\"globex\",\"role\":\"ADMIN\",\"exp\":9999999999}"
                        .getBytes(StandardCharsets.UTF_8));

        callProtected("Bearer " + parts[0] + "." + forgedPayload + "." + parts[2]).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        var ourKey = new SecretKeySpec("test-secret-test-secret-test-secret-0123456789".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("distance-engine").subject("acme-operator")
                .issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(120)) // expired two minutes ago
                .claim("tenant", "acme").claim("role", "OPERATOR").build();
        String expired = new NimbusJwtEncoder(new ImmutableSecret<>(ourKey)).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        callProtected("Bearer " + expired).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() throws Exception {
        var otherKey = new SecretKeySpec("some-other-secret-some-other-secret-1234".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        String forged = new NimbusJwtEncoder(new ImmutableSecret<>(otherKey)).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), validLookingClaims("distance-engine"))).getTokenValue();

        callProtected("Bearer " + forged).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFromAnotherIssuerIsRejectedEvenIfSignedWithOurKey() throws Exception {
        var ourKey = new SecretKeySpec("test-secret-test-secret-test-secret-0123456789".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        String foreign = new NimbusJwtEncoder(new ImmutableSecret<>(ourKey)).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), validLookingClaims("someone-else"))).getTokenValue();

        callProtected("Bearer " + foreign).andExpect(status().isUnauthorized());
    }

    private static JwtClaimsSet validLookingClaims(String issuer) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder().issuer(issuer).subject("acme-admin").issuedAt(now)
                .expiresAt(now.plusSeconds(600)).claim("tenant", "acme").claim("role", "ADMIN").build();
    }

    @Test
    void healthCheckStaysPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("UP")));
    }

    @Test
    void loginEndpointItselfNeedsNoToken() throws Exception {
        login("acme-admin", "demo-pass").andExpect(status().isOk());
    }
}

package com.moveinsync.distance.security;

import com.moveinsync.distance.persistence.AppUserEntity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Creates the signed token a client sends with every request. Tenant and role go in here
 * from the user's account and are covered by the signature, so the client can't change them.
 */
@Service
public class JwtService {

    static final String ISSUER = "distance-engine";
    static final String TENANT_CLAIM = "tenant";
    static final String ROLE_CLAIM = "role";
    static final String EMPLOYEE_CLAIM = "employeeRef";

    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder, Clock clock, SecurityProperties properties) {
        this.encoder = encoder;
        this.clock = clock;
        this.ttl = Duration.ofSeconds(properties.ttlSeconds());
    }

    public IssuedToken issue(AppUserEntity user) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim(TENANT_CLAIM, user.getTenantId())
                .claim(ROLE_CLAIM, user.getRole().name());
        if (user.getEmployeeRef() != null) {
            claims.claim(EMPLOYEE_CLAIM, user.getEmployeeRef());
        }
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        return new IssuedToken(token, ttl.toSeconds());
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}

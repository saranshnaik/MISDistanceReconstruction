package com.moveinsync.distance.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under "security.*" in application.properties.
 *
 * @param jwt           the signing secret and token lifetime
 * @param bcryptStrength cost factor for password hashing (higher is slower and safer)
 */
@ConfigurationProperties(prefix = "security")
public record SecurityProperties(Jwt jwt, int bcryptStrength) {

    public record Jwt(String secret, long ttlSeconds) {
    }

    public String secret() {
        return jwt.secret();
    }

    public long ttlSeconds() {
        return jwt.ttlSeconds();
    }
}

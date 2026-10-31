package com.moveinsync.distance.engine;

import com.moveinsync.distance.engine.model.RuleConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Label saying which rules produced a number, stored next to every result. RULE_REVISION is
 * bumped by hand when the logic or rule order changes, and a hash of all the RuleConfig
 * thresholds is added, so changing 120 km/h to 100 km/h without touching the revision still
 * gives a new version string.
 */
public final class RuleVersion {

    /** Bump when the rule logic or its order changes. */
    static final int RULE_REVISION = 1;

    private RuleVersion() {
    }

    public static String of(RuleConfig config) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(config.toString().getBytes(StandardCharsets.UTF_8));
            return "v" + RULE_REVISION + "-" + HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}

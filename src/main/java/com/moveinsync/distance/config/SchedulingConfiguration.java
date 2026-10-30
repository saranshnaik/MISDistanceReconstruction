package com.moveinsync.distance.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled jobs. Tests switch it off with recompute.enabled=false. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "recompute.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfiguration {
}

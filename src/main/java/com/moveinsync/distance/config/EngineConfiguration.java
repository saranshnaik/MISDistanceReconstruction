package com.moveinsync.distance.config;

import com.moveinsync.distance.engine.DistanceEngine;
import com.moveinsync.distance.engine.model.RuleConfig;
import com.moveinsync.distance.engine.routing.CachingRouteResolver;
import com.moveinsync.distance.engine.routing.CircuitBreaker;
import com.moveinsync.distance.engine.routing.CircuitBreakingRouteResolver;
import com.moveinsync.distance.engine.routing.RouteResolver;
import com.moveinsync.distance.engine.routing.SimulatedRoutingProvider;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one place that knows how the engine is put together. The engine is plain Java; here
 * we choose thresholds, the provider and the wrapping:
 *   engine -> circuit breaker -> cache + batching -> routing provider
 */
@Configuration
public class EngineConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RuleConfig ruleConfig() {
        return RuleConfig.defaults();
    }

    /** Stand-in for a real maps API. Swap this bean to plug in a real provider. */
    @Bean
    public SimulatedRoutingProvider routingProvider(@Value("${routing.simulated.latency-ms:0}") long latencyMs) {
        return new SimulatedRoutingProvider(latencyMs);
    }

    @Bean
    public CachingRouteResolver cachingRouteResolver(SimulatedRoutingProvider provider) {
        return new CachingRouteResolver(provider);
    }

    @Bean
    public CircuitBreaker routingCircuitBreaker(Clock clock) {
        return new CircuitBreaker(5, Duration.ofSeconds(30), clock);
    }

    @Bean
    public RouteResolver routeResolver(CachingRouteResolver caching, CircuitBreaker breaker) {
        return new CircuitBreakingRouteResolver(caching, breaker);
    }

    @Bean
    public DistanceEngine distanceEngine(RuleConfig ruleConfig, RouteResolver routeResolver) {
        return new DistanceEngine(ruleConfig, routeResolver);
    }
}

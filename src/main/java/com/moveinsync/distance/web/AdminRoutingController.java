package com.moveinsync.distance.web;

import com.moveinsync.distance.engine.routing.CircuitBreaker;
import com.moveinsync.distance.engine.routing.SimulatedRoutingProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only switch for the simulated provider, so an outage can be staged on demand (that's
 * how the failure scenario is shown live). With a real provider these endpoints wouldn't exist.
 */
@RestController
@RequestMapping("/api/admin/routing")
public class AdminRoutingController {

    private final SimulatedRoutingProvider provider;
    private final CircuitBreaker breaker;

    public AdminRoutingController(SimulatedRoutingProvider provider, CircuitBreaker breaker) {
        this.provider = provider;
        this.breaker = breaker;
    }

    public record OutageRequest(boolean down) {
    }

    public record RoutingStatus(boolean providerDown, String circuit, long timesCircuitOpened, long providerCalls) {
    }

    @GetMapping
    public RoutingStatus status() {
        return currentStatus();
    }

    /** Switch the pretend provider off ({"down": true}) or back on ({"down": false}). */
    @PostMapping("/outage")
    public RoutingStatus outage(@RequestBody OutageRequest request) {
        provider.setDown(request.down());
        return currentStatus();
    }

    private RoutingStatus currentStatus() {
        return new RoutingStatus(provider.isDown(), breaker.state().name(), breaker.timesOpened(), provider.callCount());
    }
}

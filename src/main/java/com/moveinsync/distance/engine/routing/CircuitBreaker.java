package com.moveinsync.distance.engine.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Stops us hammering a provider that is already down.
 *
 * CLOSED: normal, failures are counted and a success resets the count.
 * OPEN: too many failures in a row, everything is refused for a while.
 * HALF_OPEN: wait is over, exactly one probe request goes through. If it works we close, if
 * it fails we open again.
 *
 * Clock is injected so tests can move time without sleeping. Methods are synchronized; the
 * state is tiny and there is one check per trip, so it is not a hot path.
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openFor;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private Instant openedAt;
    private boolean probeInFlight = false;
    private long timesOpened = 0;

    public CircuitBreaker(int failureThreshold, Duration openFor, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.openFor = openFor;
        this.clock = clock;
    }

    /** Ask before calling the provider. False means don't even try. */
    public synchronized boolean allowRequest() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (clock.instant().isBefore(openedAt.plus(openFor))) {
                return false;
            }
            state = State.HALF_OPEN;
        }
        // half-open: one probe at a time
        if (probeInFlight) {
            return false;
        }
        probeInFlight = true;
        return true;
    }

    /**
     * True while the circuit is open and still waiting. Unlike allowRequest() it only looks and
     * never lets a probe through, which is what the background job wants: stay quiet until the
     * provider has had time to recover.
     */
    public synchronized boolean isRefusingRequests() {
        return state == State.OPEN && clock.instant().isBefore(openedAt.plus(openFor));
    }

    public synchronized void recordSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        probeInFlight = false;
    }

    public synchronized void recordFailure() {
        probeInFlight = false;
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.instant();
        consecutiveFailures = 0;
        timesOpened++;
    }

    public synchronized State state() {
        return state;
    }

    /** How often the circuit has tripped since start-up (used as a metric). */
    public synchronized long timesOpened() {
        return timesOpened;
    }
}

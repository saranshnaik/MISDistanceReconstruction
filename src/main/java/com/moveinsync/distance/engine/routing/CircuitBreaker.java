package com.moveinsync.distance.engine.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Stops us hammering a routing provider that is already down.
 *
 *   CLOSED    normal. Failures are counted; a success resets the count.
 *   OPEN      too many failures in a row. Every request is refused straight away for a while.
 *   HALF_OPEN the wait is over. Exactly ONE request (the probe) is let through to test the
 *             provider. If it works we go back to CLOSED, if it fails we go back to OPEN.
 *
 * The clock is passed in so tests can move time forward without sleeping.
 * Methods are synchronized: the state is tiny and this is not a hot path (one check per trip).
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

    /** Ask before calling the provider. False means: do not even try. */
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
        // HALF_OPEN: only one probe at a time
        if (probeInFlight) {
            return false;
        }
        probeInFlight = true;
        return true;
    }

    /**
     * True while the circuit is open and its wait is not over yet. Unlike allowRequest() this
     * only looks: it never lets a probe through. Handy for background work that should simply
     * stay quiet until the provider has had time to recover.
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

    /** How often the circuit has tripped since start-up; handy as a metric. */
    public synchronized long timesOpened() {
        return timesOpened;
    }
}

package com.moveinsync.distance.engine.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moveinsync.distance.engine.routing.CircuitBreaker.State;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T08:00:00Z"));
    private final CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(30), clock);

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            assertTrue(breaker.allowRequest());
            breaker.recordFailure();
        }
    }

    @Test
    void startsClosedAndAllowsRequests() {
        assertEquals(State.CLOSED, breaker.state());
        assertTrue(breaker.allowRequest());
    }

    @Test
    void staysClosedBelowTheFailureThreshold() {
        fail(4);

        assertEquals(State.CLOSED, breaker.state());
        assertTrue(breaker.allowRequest());
    }

    @Test
    void opensAfterFiveFailuresInARow() {
        fail(5);

        assertEquals(State.OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
        assertEquals(1, breaker.timesOpened());
    }

    @Test
    void aSuccessInTheMiddleResetsTheCount() {
        fail(4);
        breaker.allowRequest();
        breaker.recordSuccess();
        fail(4);

        assertEquals(State.CLOSED, breaker.state());
    }

    @Test
    void staysOpenUntilTheWaitIsOver() {
        fail(5);

        clock.advance(Duration.ofSeconds(29));
        assertFalse(breaker.allowRequest());
        assertEquals(State.OPEN, breaker.state());
    }

    @Test
    void afterTheWaitExactlyOneProbeIsLetThrough() {
        fail(5);
        clock.advance(Duration.ofSeconds(30));

        assertTrue(breaker.allowRequest());   // the probe
        assertEquals(State.HALF_OPEN, breaker.state());
        assertFalse(breaker.allowRequest());  // everyone else still waits
    }

    @Test
    void successfulProbeClosesTheCircuit() {
        fail(5);
        clock.advance(Duration.ofSeconds(30));
        breaker.allowRequest();

        breaker.recordSuccess();

        assertEquals(State.CLOSED, breaker.state());
        assertTrue(breaker.allowRequest());
    }

    @Test
    void failedProbeOpensItAgainForAnotherFullWait() {
        fail(5);
        clock.advance(Duration.ofSeconds(30));
        breaker.allowRequest();

        breaker.recordFailure();

        assertEquals(State.OPEN, breaker.state());
        assertEquals(2, breaker.timesOpened());
        clock.advance(Duration.ofSeconds(29));
        assertFalse(breaker.allowRequest());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(breaker.allowRequest());
    }

    @Test
    void whenManyThreadsArriveTogetherOnlyOneGetsTheProbe() throws Exception {
        fail(5);
        clock.advance(Duration.ofSeconds(30));
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return breaker.allowRequest();
            }));
        }
        go.countDown();

        int allowed = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                allowed++;
            }
        }
        pool.shutdown();
        assertEquals(1, allowed);
    }

    @Test
    void isRefusingRequestsOnlyWhileOpenAndWaitingAndNeverStartsAProbe() {
        assertFalse(breaker.isRefusingRequests());
        fail(5);
        assertTrue(breaker.isRefusingRequests());

        clock.advance(Duration.ofSeconds(30));
        assertFalse(breaker.isRefusingRequests());
        assertEquals(State.OPEN, breaker.state());   // looking did not move it to HALF_OPEN
        assertTrue(breaker.allowRequest());           // so the probe is still available
    }
}

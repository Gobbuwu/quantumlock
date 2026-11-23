package com.quantumlock.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** The rate limiter's counting, windows, Retry-After values and memory cap, with a test clock (KAN-31). */
class RateLimiterTest {

    /** A clock that moves only when told to. */
    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-23T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();

    @Test
    void theLimitIsInclusiveAndTheWaitRunsToTheEndOfTheWindow() {
        RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(1), 100, clock);

        assertEquals(0, limiter.acquire("a"));
        assertEquals(0, limiter.acquire("a"));
        clock.advance(Duration.ofSeconds(20));
        assertEquals(0, limiter.acquire("a"));
        assertEquals(40, limiter.acquire("a"), "the fourth in the minute waits until the minute is over");
        clock.advance(Duration.ofMillis(39_001));
        assertEquals(1, limiter.acquire("a"), "never less than one second");
        clock.advance(Duration.ofMillis(999));
        assertEquals(0, limiter.acquire("a"), "a new window starts");
    }

    @Test
    void refusedEventsAreNotCountedSoTheyCannotExtendTheWait() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), 100, clock);
        assertEquals(0, limiter.acquire("a"));
        for (int i = 0; i < 50; i++) {
            limiter.acquire("a");
        }
        clock.advance(Duration.ofMinutes(1));
        assertEquals(0, limiter.acquire("a"));
    }

    @Test
    void keysAreCountedSeparately() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), 100, clock);
        assertEquals(0, limiter.acquire("a"));
        assertEquals(0, limiter.acquire("b"));
        assertTrue(limiter.acquire("a") > 0);
        assertTrue(limiter.acquire("b") > 0);
    }

    @Test
    void failuresAreRecordedAndCheckedSeparately() {
        RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(1), 100, clock);

        assertEquals(0, limiter.waitSeconds("10.0.0.1"));
        assertFalse(limiter.record("10.0.0.1"));
        assertEquals(0, limiter.waitSeconds("10.0.0.1"), "checking counts nothing");
        assertEquals(0, limiter.waitSeconds("10.0.0.1"));
        assertTrue(limiter.record("10.0.0.1"), "this failure reached the limit");
        assertEquals(60, limiter.waitSeconds("10.0.0.1"));
        assertFalse(limiter.record("10.0.0.1"), "reported once per window");
        clock.advance(Duration.ofMinutes(1));
        assertEquals(0, limiter.waitSeconds("10.0.0.1"));
    }

    @Test
    void memoryIsCappedAndAFullTableRefusesNewKeys() {
        RateLimiter limiter = new RateLimiter(5, Duration.ofMinutes(1), 3, clock);
        for (String key : List.of("a", "b", "c")) {
            assertEquals(0, limiter.acquire(key));
        }

        assertEquals(60, limiter.acquire("d"), "a made-up key cannot push out the ones being counted");
        assertEquals(60, limiter.waitSeconds("e"));
        assertFalse(limiter.record("f"));
        assertEquals(3, limiter.size());
        assertEquals(0, limiter.acquire("a"), "keys already tracked carry on");

        clock.advance(Duration.ofMinutes(1));
        assertEquals(0, limiter.acquire("d"), "finished windows make room");
        assertEquals(1, limiter.size());
    }

    @Test
    void manyThreadsNeverGetMoreThanTheLimit() throws Exception {
        RateLimiter limiter = new RateLimiter(100, Duration.ofMinutes(1), 100, clock);
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 100; i++) {
                        if (limiter.acquire("shared") == 0) {
                            allowed.incrementAndGet();
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(100, allowed.get());
    }

    @Test
    void badSettingsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, Duration.ofMinutes(1), 1, clock));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(1, Duration.ZERO, 1, clock));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(1, Duration.ofMinutes(1), 0, clock));
    }
}

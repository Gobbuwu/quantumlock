package com.quantumlock.security;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Counts events per key (an API key id, a client address) in fixed windows, by default one minute (KAN-31).
 *
 * <p>Memory is capped: at most {@code maxEntries} keys are tracked. When the table is full even after dropping
 * finished windows, a key it does not know yet is treated as over the limit, so a flood of made-up keys can
 * neither use up memory nor slip past the limit. Thread-safe.
 */
public final class RateLimiter {

    /** Most keys tracked at once. */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final int limit;
    private final long windowMillis;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    private static final class Window {
        final long start;
        int count;

        Window(long start) {
            this.start = start;
        }
    }

    public RateLimiter(int limit, Duration window, int maxEntries, Clock clock) {
        if (limit < 1 || window.isNegative() || window.isZero() || maxEntries < 1) {
            throw new IllegalArgumentException("limit, window and maxEntries must be positive");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /**
     * Counts one event for the key unless it is already at the limit. Returns 0 when the event may go ahead,
     * otherwise the whole seconds to wait (at least 1), for a Retry-After header.
     */
    public synchronized long acquire(String key) {
        long now = clock.millis();
        Window window = current(key, now);
        if (window == null) {
            return waitSeconds(now, now);
        }
        if (window.count >= limit) {
            return waitSeconds(window.start, now);
        }
        window.count++;
        return 0;
    }

    /** Seconds to wait before the key may go ahead, or 0. Counts nothing. */
    public synchronized long waitSeconds(String key) {
        long now = clock.millis();
        Window window = windows.get(key);
        if (window != null && now - window.start >= windowMillis) {
            windows.remove(key);
            window = null;
        }
        if (window == null) {
            return roomFor(now) ? 0 : waitSeconds(now, now);
        }
        return window.count >= limit ? waitSeconds(window.start, now) : 0;
    }

    /** Counts one event for the key, whatever the count. True when this event reached the limit. */
    public synchronized boolean record(String key) {
        Window window = current(key, clock.millis());
        if (window == null) {
            return false;
        }
        window.count++;
        return window.count == limit;
    }

    /** Number of keys tracked right now, for tests. */
    synchronized int size() {
        return windows.size();
    }

    /** The key's window that is running now, started if needed. Null when the table is full. */
    private Window current(String key, long now) {
        Window window = windows.get(key);
        if (window != null && now - window.start < windowMillis) {
            return window;
        }
        if (window == null && !roomFor(now)) {
            return null;
        }
        window = new Window(now);
        windows.put(key, window);
        return window;
    }

    /** True when one more key fits, after dropping the windows that have ended. */
    private boolean roomFor(long now) {
        if (windows.size() < maxEntries) {
            return true;
        }
        for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
            if (now - it.next().start >= windowMillis) {
                it.remove();
            }
        }
        return windows.size() < maxEntries;
    }

    private long waitSeconds(long windowStart, long now) {
        long millis = windowStart + windowMillis - now;
        return Math.max(1, (millis + 999) / 1000);
    }
}

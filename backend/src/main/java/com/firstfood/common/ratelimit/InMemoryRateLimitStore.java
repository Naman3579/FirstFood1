package com.firstfood.common.ratelimit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Fixed-window counters held in this JVM only. NOT a substitute for the shared store - with N replicas the
 * effective budget is N times larger - but it keeps the API from becoming unlimited while Redis is down, and
 * makes the limiter testable without Redis.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    /** Hard cap so a flood of distinct keys cannot grow the map without bound. */
    private static final int MAX_KEYS = 100_000;

    private record Window(long count, long resetAtMillis) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    /** Sliding-window hit timestamps (millis), oldest first. Mutated only inside {@code compute}, so per-key atomic. */
    private final ConcurrentHashMap<String, ArrayDeque<Long>> sliding = new ConcurrentHashMap<>();
    private final LongSupplier nowMillis;

    public InMemoryRateLimitStore() {
        this(System::currentTimeMillis);
    }

    public InMemoryRateLimitStore(LongSupplier nowMillis) {
        this.nowMillis = nowMillis;
    }

    @Override
    public RateLimitDecision hit(String key, int limit, Duration window) {
        long now = nowMillis.getAsLong();
        evictIfFull(now);
        Window updated = windows.compute(key, (k, current) -> {
            if (current == null || current.resetAtMillis() <= now) {
                return new Window(1, now + window.toMillis());
            }
            return new Window(current.count() + 1, current.resetAtMillis());
        });
        return RateLimitDecision.of(updated.count(), limit, updated.resetAtMillis() - now);
    }

    @Override
    public RateLimitDecision peek(String key, int limit, Duration window) {
        long now = nowMillis.getAsLong();
        Window current = windows.get(key);
        if (current == null || current.resetAtMillis() <= now) {
            return RateLimitDecision.of(0, limit, window.toMillis());
        }
        return RateLimitDecision.of(current.count(), limit, current.resetAtMillis() - now);
    }

    @Override
    public RateLimitDecision hitSliding(String key, int limit, Duration window) {
        long now = nowMillis.getAsLong();
        long windowMillis = window.toMillis();
        evictSlidingIfFull(now, windowMillis);
        long[] outcome = new long[2]; // [count, retryAfterMillis]
        sliding.compute(key, (k, hits) -> {
            ArrayDeque<Long> deque = hits == null ? new ArrayDeque<>() : hits;
            while (!deque.isEmpty() && deque.peekFirst() <= now - windowMillis) {
                deque.pollFirst();
            }
            if (deque.size() < limit) {
                deque.addLast(now);
                outcome[0] = deque.size();
            } else {
                outcome[0] = (long) limit + 1; // denied: not recorded
            }
            outcome[1] = deque.isEmpty() ? windowMillis : deque.peekFirst() + windowMillis - now;
            return deque;
        });
        return RateLimitDecision.of(outcome[0], limit, outcome[1]);
    }

    @Override
    public RateLimitDecision peekSliding(String key, int limit, Duration window) {
        long now = nowMillis.getAsLong();
        long windowMillis = window.toMillis();
        ArrayDeque<Long> hits = sliding.get(key);
        if (hits == null) {
            return RateLimitDecision.of(0, limit, windowMillis);
        }
        long[] outcome = new long[2];
        sliding.computeIfPresent(key, (k, deque) -> {
            while (!deque.isEmpty() && deque.peekFirst() <= now - windowMillis) {
                deque.pollFirst();
            }
            outcome[0] = deque.size();
            outcome[1] = deque.isEmpty() ? windowMillis : deque.peekFirst() + windowMillis - now;
            return deque.isEmpty() ? null : deque;
        });
        return RateLimitDecision.of(outcome[0], limit, outcome[1] == 0 ? windowMillis : outcome[1]);
    }

    @Override
    public void releaseSliding(String key) {
        sliding.computeIfPresent(key, (k, deque) -> {
            deque.pollLast();
            return deque.isEmpty() ? null : deque;
        });
    }

    @Override
    public void reset(String key) {
        windows.remove(key);
        sliding.remove(key);
    }

    int size() {
        return windows.size() + sliding.size();
    }

    private void evictSlidingIfFull(long now, long windowMillis) {
        if (sliding.size() < MAX_KEYS) {
            return;
        }
        sliding.entrySet().removeIf(e -> e.getValue().isEmpty() || e.getValue().peekLast() <= now - windowMillis);
        if (sliding.size() >= MAX_KEYS) {
            sliding.clear();
        }
    }

    private void evictIfFull(long now) {
        if (windows.size() < MAX_KEYS) {
            return;
        }
        windows.entrySet().removeIf(e -> e.getValue().resetAtMillis() <= now);
        if (windows.size() >= MAX_KEYS) {
            windows.clear(); // still full of live windows: shed everything rather than grow
        }
    }
}

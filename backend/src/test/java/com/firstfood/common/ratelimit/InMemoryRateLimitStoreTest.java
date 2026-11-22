package com.firstfood.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryRateLimitStoreTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    @Test
    void allowsUpToTheLimitThenDeniesWithARetryAfter() {
        long[] now = {1_000_000};
        var store = new InMemoryRateLimitStore(() -> now[0]);

        assertThat(store.hit("k", 3, WINDOW).allowed()).isTrue();
        assertThat(store.hit("k", 3, WINDOW).allowed()).isTrue();
        assertThat(store.hit("k", 3, WINDOW).allowed()).isTrue();
        RateLimitDecision denied = store.hit("k", 3, WINDOW);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void windowExpiresAndCountingStartsAgain() {
        long[] now = {1_000_000};
        var store = new InMemoryRateLimitStore(() -> now[0]);
        store.hit("k", 1, WINDOW);
        assertThat(store.hit("k", 1, WINDOW).allowed()).isFalse();

        now[0] += 30_000;
        assertThat(store.hit("k", 1, WINDOW).retryAfterSeconds()).isEqualTo(30);

        now[0] += 30_001;
        assertThat(store.hit("k", 1, WINDOW).allowed()).isTrue();
    }

    @Test
    void peekNeverCountsAndResetForgets() {
        var store = new InMemoryRateLimitStore();
        store.hit("k", 5, WINDOW);
        assertThat(store.peek("k", 5, WINDOW).count()).isEqualTo(1);
        assertThat(store.peek("k", 5, WINDOW).count()).isEqualTo(1);

        store.reset("k");
        assertThat(store.peek("k", 5, WINDOW).count()).isZero();
    }

    @Test
    void parallelHitsNeverAllowMoreThanTheLimit() throws Exception {
        var store = new InMemoryRateLimitStore();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (store.hit("c", 50, WINDOW).allowed()) {
                    allowed.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(allowed.get()).isEqualTo(50);
    }

    @Test
    void slidingWindowAllowsLimitInAnyWindowSpanNotDoubleAcrossABoundary() {
        long[] now = {1_000_000};
        var store = new InMemoryRateLimitStore(() -> now[0]);

        // Spend the whole budget at the very end of a "fixed" window...
        now[0] += 50_000;
        for (int i = 0; i < 3; i++) {
            assertThat(store.hitSliding("k", 3, WINDOW).allowed()).isTrue();
        }
        // ...and 15 s later (a fixed window would have reset by now) the budget is still spent.
        now[0] += 15_000;
        RateLimitDecision denied = store.hitSliding("k", 3, WINDOW);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.count()).isEqualTo(4);
        assertThat(denied.retryAfterSeconds()).isEqualTo(45);

        // Once the oldest hits slide out, room appears again.
        now[0] += 45_001;
        assertThat(store.hitSliding("k", 3, WINDOW).allowed()).isTrue();
    }

    @Test
    void slidingWindowFreesOneSlotAtATime() {
        long[] now = {1_000_000};
        var store = new InMemoryRateLimitStore(() -> now[0]);
        store.hitSliding("k", 2, WINDOW);           // t = 0
        now[0] += 10_000;
        store.hitSliding("k", 2, WINDOW);           // t = 10 s
        assertThat(store.hitSliding("k", 2, WINDOW).allowed()).isFalse();

        now[0] += 50_001;                            // first hit left the window, second has not
        assertThat(store.hitSliding("k", 2, WINDOW).allowed()).isTrue();
        assertThat(store.hitSliding("k", 2, WINDOW).allowed()).isFalse();
    }

    @Test
    void deniedSlidingHitsAreNotRecordedSoABlockedClientCannotExtendItsOwnLock() {
        long[] now = {1_000_000};
        var store = new InMemoryRateLimitStore(() -> now[0]);
        store.hitSliding("k", 1, WINDOW);
        for (int i = 0; i < 20; i++) {
            now[0] += 1_000;
            assertThat(store.hitSliding("k", 1, WINDOW).allowed()).isFalse();
        }
        now[0] = 1_000_000 + 60_001;
        assertThat(store.hitSliding("k", 1, WINDOW).allowed()).isTrue();
    }

    @Test
    void slidingPeekNeverCountsAndResetForgetsBothKinds() {
        var store = new InMemoryRateLimitStore();
        store.hitSliding("k", 5, WINDOW);
        store.hitSliding("k", 5, WINDOW);
        assertThat(store.peekSliding("k", 5, WINDOW).count()).isEqualTo(2);
        assertThat(store.peekSliding("k", 5, WINDOW).count()).isEqualTo(2);
        assertThat(store.peekSliding("missing", 5, WINDOW).count()).isZero();

        store.reset("k");
        assertThat(store.peekSliding("k", 5, WINDOW).count()).isZero();
    }

    @Test
    void parallelSlidingHitsNeverExceedTheLimit() throws Exception {
        var store = new InMemoryRateLimitStore();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            results.add(pool.submit(() -> store.hitSliding("k", 5, WINDOW).allowed()));
        }
        int allowed = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                allowed++;
            }
        }
        pool.shutdown();
        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void releaseSlidingGivesBackTheNewestHitAndIsANoOpWhenEmpty() {
        long[] now = {1_000_000};
        var store = new InMemoryRateLimitStore(() -> now[0]);

        store.releaseSliding("missing"); // must not throw

        store.hitSliding("k", 2, WINDOW);
        store.hitSliding("k", 2, WINDOW);
        assertThat(store.hitSliding("k", 2, WINDOW).allowed()).isFalse();

        store.releaseSliding("k");

        assertThat(store.peekSliding("k", 2, WINDOW).count()).isEqualTo(1);
        assertThat(store.hitSliding("k", 2, WINDOW).allowed()).isTrue();
        assertThat(store.hitSliding("k", 2, WINDOW).allowed()).isFalse();
    }
}

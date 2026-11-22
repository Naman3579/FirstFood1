package com.firstfood.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The Lua script's guarantees, against a real Redis-compatible server. */
class RedisRateLimitStoreIntegrationTest extends AbstractIntegrationTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    @Autowired
    RedisRateLimitStore store;

    @Autowired
    StringRedisTemplate redis;

    private String freshKey() {
        return "rl-it-store:" + UUID.randomUUID();
    }

    @Test
    void firstHitStartsTheWindowWithAnExpiry() {
        String key = freshKey();

        RateLimitDecision decision = store.hit(key, 5, WINDOW);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.count()).isEqualTo(1);
        Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(WINDOW.toMillis());
    }

    @Test
    void aCounterWithoutAnExpiryIsRepairedOnTheNextHit() {
        // The failure mode of the old INCR-then-EXPIRE code: a crash between the two left a counter that never expired.
        String key = freshKey();
        redis.opsForValue().set(key, "9");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isEqualTo(-1L);

        RateLimitDecision decision = store.hit(key, 5, WINDOW);

        assertThat(decision.count()).isEqualTo(10);
        assertThat(decision.allowed()).isFalse();
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void theWindowIsNotExtendedByLaterHits() throws Exception {
        String key = freshKey();
        store.hit(key, 5, Duration.ofSeconds(2));
        Thread.sleep(500);
        store.hit(key, 5, Duration.ofSeconds(2));

        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThan(1700L);
    }

    @Test
    void counterRestartsAfterTheWindowEnds() throws Exception {
        String key = freshKey();
        store.hit(key, 1, Duration.ofMillis(300));
        assertThat(store.hit(key, 1, Duration.ofMillis(300)).allowed()).isFalse();

        Thread.sleep(450);

        assertThat(store.hit(key, 1, Duration.ofMillis(300)).allowed()).isTrue();
    }

    @Test
    void peekDoesNotCountAndResetForgets() {
        String key = freshKey();
        assertThat(store.peek(key, 5, WINDOW).count()).isZero();
        store.hit(key, 5, WINDOW);
        store.hit(key, 5, WINDOW);
        assertThat(store.peek(key, 5, WINDOW).count()).isEqualTo(2);
        assertThat(store.peek(key, 5, WINDOW).count()).isEqualTo(2);

        store.reset(key);

        assertThat(store.peek(key, 5, WINDOW).count()).isZero();
    }

    @Test
    void parallelHitsFromManyThreadsNeverExceedTheLimit() throws Exception {
        String key = freshKey();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (store.hit(key, 50, WINDOW).allowed()) {
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
    void slidingWindowAllowsOnlyTheLimitAndDeniedHitsAreNotRecorded() {
        String key = freshKey();

        for (int i = 1; i <= 3; i++) {
            RateLimitDecision d = store.hitSliding(key, 3, WINDOW);
            assertThat(d.allowed()).isTrue();
            assertThat(d.count()).isEqualTo(i);
        }
        for (int i = 0; i < 10; i++) {
            RateLimitDecision denied = store.hitSliding(key, 3, WINDOW);
            assertThat(denied.allowed()).isFalse();
            assertThat(denied.count()).isEqualTo(4);
            assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
        }
        // Storage stays bounded by the limit, and the key always carries an expiry.
        assertThat(redis.opsForZSet().zCard(key + ":sw")).isEqualTo(3L);
        assertThat(redis.getExpire(key + ":sw", TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void slidingWindowFreesSlotsAsOldHitsLeaveTheWindow() throws Exception {
        String key = freshKey();
        Duration shortWindow = Duration.ofMillis(800);
        assertThat(store.hitSliding(key, 1, shortWindow).allowed()).isTrue();
        assertThat(store.hitSliding(key, 1, shortWindow).allowed()).isFalse();

        Thread.sleep(900);

        assertThat(store.hitSliding(key, 1, shortWindow).allowed()).isTrue();
    }

    @Test
    void slidingPeekDoesNotCountAndResetClearsIt() {
        String key = freshKey();
        store.hitSliding(key, 5, WINDOW);
        store.hitSliding(key, 5, WINDOW);

        assertThat(store.peekSliding(key, 5, WINDOW).count()).isEqualTo(2);
        assertThat(store.peekSliding(key, 5, WINDOW).count()).isEqualTo(2);
        assertThat(store.peekSliding("rl-it-store:none-" + UUID.randomUUID(), 5, WINDOW).count()).isZero();

        store.reset(key);

        assertThat(store.peekSliding(key, 5, WINDOW).count()).isZero();
    }

    @Test
    void parallelSlidingHitsNeverExceedTheLimit() throws Exception {
        String key = freshKey();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (store.hitSliding(key, 50, WINDOW).allowed()) {
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
}

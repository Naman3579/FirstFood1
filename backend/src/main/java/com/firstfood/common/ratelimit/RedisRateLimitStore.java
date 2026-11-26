package com.firstfood.common.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed fixed-window counters. Works against Redis, Valkey and Upstash (all support EVAL).
 *
 * The increment and the expiry are ONE Lua script, so they cannot be separated by a crash or a timeout.
 * The script also repairs a key that somehow has no TTL (PTTL < 0) by arming it on the next hit, so a
 * legacy or manually created counter can never become a permanent lock-out.
 */
@Component
public class RedisRateLimitStore implements RateLimitStore {

    private static final String HIT_LUA = """
            local count = redis.call('INCR', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if count == 1 or ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {count, ttl}
            """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> HIT_SCRIPT = new DefaultRedisScript<>(HIT_LUA, List.class);

    /**
     * Sliding-window LOG in a sorted set (score = hit time in ms). One script = trim old hits, count, record the new hit
     * only if there is room, re-arm the expiry: atomic, so parallel requests can never overshoot the limit.
     * Denied hits are not recorded, so the set never holds more than {@code limit} entries. Returns {count, retryMs}.
     * ARGV: 1 = now ms, 2 = window ms, 3 = limit, 4 = unique member.
     */
    private static final String SLIDING_HIT_LUA = """
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            local count = redis.call('ZCARD', KEYS[1])
            local denied = 0
            if count < limit then
              redis.call('ZADD', KEYS[1], now, ARGV[4])
              count = count + 1
            else
              denied = 1
            end
            redis.call('PEXPIRE', KEYS[1], window)
            local retry = window
            local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            if oldest[2] then retry = tonumber(oldest[2]) + window - now end
            if denied == 1 then count = limit + 1 end
            return {count, retry}
            """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SLIDING_HIT_SCRIPT = new DefaultRedisScript<>(SLIDING_HIT_LUA, List.class);

    /**
     * Sliding-window keys get their own suffix so a deployment never meets an old fixed-window STRING value under the
     * same name (WRONGTYPE would fail every request closed). Old counters simply age out within an hour.
     */
    private static final String SLIDING_SUFFIX = ":sw";

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitDecision hit(String key, int limit, Duration window) {
        try {
            List<Object> result = (List<Object>) redis.execute(HIT_SCRIPT, List.of(key), Long.toString(window.toMillis()));
            if (result == null || result.size() < 2) {
                throw new RateLimitStoreException("Unexpected rate-limit script result: " + result);
            }
            long count = ((Number) result.get(0)).longValue();
            long ttl = ((Number) result.get(1)).longValue();
            return RateLimitDecision.of(count, limit, ttl);
        } catch (RateLimitStoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RateLimitStoreException("Redis rate-limit hit failed", e);
        }
    }

    @Override
    public RateLimitDecision peek(String key, int limit, Duration window) {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) {
                return RateLimitDecision.of(0, limit, window.toMillis());
            }
            Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
            long ttlMillis = (ttl == null || ttl < 0) ? window.toMillis() : ttl;
            return RateLimitDecision.of(Long.parseLong(raw), limit, ttlMillis);
        } catch (RuntimeException e) {
            throw new RateLimitStoreException("Redis rate-limit peek failed", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitDecision hitSliding(String key, int limit, Duration window) {
        try {
            List<Object> result = (List<Object>) redis.execute(SLIDING_HIT_SCRIPT, List.of(key + SLIDING_SUFFIX),
                    Long.toString(System.currentTimeMillis()), Long.toString(window.toMillis()),
                    Integer.toString(limit), UUID.randomUUID().toString());
            if (result == null || result.size() < 2) {
                throw new RateLimitStoreException("Unexpected sliding-window script result: " + result);
            }
            return RateLimitDecision.of(((Number) result.get(0)).longValue(), limit, ((Number) result.get(1)).longValue());
        } catch (RateLimitStoreException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RateLimitStoreException("Redis sliding-window hit failed", e);
        }
    }

    @Override
    public RateLimitDecision peekSliding(String key, int limit, Duration window) {
        try {
            String slidingKey = key + SLIDING_SUFFIX;
            long now = System.currentTimeMillis();
            double windowStart = now - window.toMillis();
            var zset = redis.opsForZSet();
            Long count = zset.count(slidingKey, windowStart + 1, Double.POSITIVE_INFINITY);
            if (count == null || count == 0) {
                return RateLimitDecision.of(0, limit, window.toMillis());
            }
            var oldest = zset.rangeByScoreWithScores(slidingKey, windowStart + 1, Double.POSITIVE_INFINITY, 0, 1);
            long retry = window.toMillis();
            if (oldest != null && !oldest.isEmpty() && oldest.iterator().next().getScore() != null) {
                retry = (long) (oldest.iterator().next().getScore() + window.toMillis() - now);
            }
            return RateLimitDecision.of(count, limit, Math.max(retry, 1));
        } catch (RuntimeException e) {
            throw new RateLimitStoreException("Redis sliding-window peek failed", e);
        }
    }

    @Override
    public void releaseSliding(String key) {
        try {
            // ZPOPMAX is one atomic command: removes the newest recorded hit, no-op on an empty/missing set.
            redis.opsForZSet().popMax(key + SLIDING_SUFFIX);
        } catch (RuntimeException e) {
            throw new RateLimitStoreException("Redis sliding-window release failed", e);
        }
    }

    @Override
    public void reset(String key) {
        try {
            redis.delete(List.of(key, key + SLIDING_SUFFIX));
        } catch (RuntimeException e) {
            throw new RateLimitStoreException("Redis rate-limit reset failed", e);
        }
    }
}

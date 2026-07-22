package com.medha.urlshortenerservice.service;

import com.medha.urlshortenerservice.config.RedisKeys;
import com.medha.urlshortenerservice.config.UrlShortenerProperties;
import com.medha.urlshortenerservice.dto.PopularUrlResponse;
import com.medha.urlshortenerservice.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Owns every click-related Redis data structure:
 * <ul>
 *     <li>{@code clicks:pending:{code}} - a per-code counter (String, incremented via {@code INCR})
 *         holding clicks that have not yet been written to MySQL.</li>
 *     <li>{@code clicks:dirty-set} - a Set of short codes with a non-zero pending counter, so the
 *         scheduled flush never has to run a {@code KEYS} scan over the keyspace.</li>
 *     <li>{@code urls:leaderboard} - a Sorted Set (ZSET) ranking every short code by total clicks,
 *         updated in the same request path via {@code ZINCRBY}.</li>
 * </ul>
 * MySQL's {@code click_count} column is the durable total; Redis holds only the
 * delta since the last flush, which keeps the hot write path (one redirect =
 * one Redis round trip) off the relational database entirely.
 */
@Service
public class ClickTrackingService {

    private static final Logger log = LoggerFactory.getLogger(ClickTrackingService.class);

    private final StringRedisTemplate redisTemplate;
    private final UrlMappingRepository urlMappingRepository;
    private final UrlShortenerProperties properties;

    public ClickTrackingService(StringRedisTemplate redisTemplate,
                                 UrlMappingRepository urlMappingRepository,
                                 UrlShortenerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.urlMappingRepository = urlMappingRepository;
        this.properties = properties;
    }

    /** Records one click against a short code: pending counter, dirty set, and leaderboard. */
    public void recordClick(String shortCode) {
        redisTemplate.opsForValue().increment(RedisKeys.clickPendingKey(shortCode));
        redisTemplate.opsForSet().add(RedisKeys.CLICK_DIRTY_SET, shortCode);
        redisTemplate.opsForZSet().incrementScore(RedisKeys.LEADERBOARD, shortCode, 1);
    }

    /** Pending (not-yet-flushed) click count for a code, used when computing live stats. */
    public long getPendingClicks(String shortCode) {
        String value = redisTemplate.opsForValue().get(RedisKeys.clickPendingKey(shortCode));
        return value == null ? 0L : Long.parseLong(value);
    }

    /** Removes every cached artifact for a short code, e.g. when it is deleted. */
    public void evictAll(String shortCode) {
        redisTemplate.delete(RedisKeys.urlCacheKey(shortCode));
        redisTemplate.delete(RedisKeys.clickPendingKey(shortCode));
        redisTemplate.opsForSet().remove(RedisKeys.CLICK_DIRTY_SET, shortCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LEADERBOARD, shortCode);
    }

    /** Uses the configured default leaderboard size ({@code app.click-tracking.leaderboard-size}). */
    public List<PopularUrlResponse> topPopular() {
        return topPopular(properties.getClickTracking().getLeaderboardSize());
    }

    public List<PopularUrlResponse> topPopular(int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(RedisKeys.LEADERBOARD, 0, limit - 1);
        List<PopularUrlResponse> result = new ArrayList<>();
        if (tuples == null) {
            return result;
        }
        int rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            double score = tuple.getScore() == null ? 0.0 : tuple.getScore();
            result.add(new PopularUrlResponse(rank++, tuple.getValue(), (long) score));
        }
        return result;
    }

    /**
     * Periodically drains the dirty set, atomically reads-and-clears each
     * code's pending counter with {@code GETDEL}, and applies the delta to
     * MySQL as a single {@code UPDATE ... SET click_count = click_count + ?}.
     * Runs on a fixed delay so a slow flush cannot overlap with itself.
     */
    @Scheduled(fixedDelayString = "${app.click-tracking.flush-interval-ms}")
    public void flushPendingClicks() {
        Set<String> dirtyCodes = redisTemplate.opsForSet().members(RedisKeys.CLICK_DIRTY_SET);
        if (dirtyCodes == null || dirtyCodes.isEmpty()) {
            return;
        }
        for (String shortCode : dirtyCodes) {
            String pendingRaw = redisTemplate.opsForValue().getAndDelete(RedisKeys.clickPendingKey(shortCode));
            redisTemplate.opsForSet().remove(RedisKeys.CLICK_DIRTY_SET, shortCode);
            if (pendingRaw == null) {
                continue;
            }
            long pending = Long.parseLong(pendingRaw);
            if (pending <= 0) {
                continue;
            }
            int updated = urlMappingRepository.incrementClickCount(shortCode, pending);
            if (updated == 0) {
                log.warn("Flushed {} pending clicks for unknown short code '{}' (mapping deleted?)", pending, shortCode);
            } else {
                log.debug("Flushed {} pending clicks for short code '{}'", pending, shortCode);
            }
        }
    }
}

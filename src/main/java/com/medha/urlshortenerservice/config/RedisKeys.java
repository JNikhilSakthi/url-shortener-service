package com.medha.urlshortenerservice.config;

/**
 * Single source of truth for every Redis key pattern used across the
 * application, so key naming conventions stay consistent and greppable.
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** String: cache-aside entry mapping a short code to its original URL. */
    public static final String URL_CACHE_PREFIX = "url:cache:";

    /** String (counter): pending, not-yet-flushed click count for a short code. */
    public static final String CLICK_PENDING_PREFIX = "clicks:pending:";

    /** Set: short codes that currently have a non-zero pending click count. */
    public static final String CLICK_DIRTY_SET = "clicks:dirty-set";

    /** Sorted set: all-time click leaderboard, member = short code, score = total clicks. */
    public static final String LEADERBOARD = "urls:leaderboard";

    /** String (counter): atomic sequence used to mint new short codes. */
    public static final String ID_SEQUENCE = "url:id:sequence";

    /** String (counter): fixed-window rate-limit counter, one per client key. */
    public static final String RATE_LIMIT_PREFIX = "ratelimit:create:";

    public static String urlCacheKey(String shortCode) {
        return URL_CACHE_PREFIX + shortCode;
    }

    public static String clickPendingKey(String shortCode) {
        return CLICK_PENDING_PREFIX + shortCode;
    }

    public static String rateLimitKey(String clientKey) {
        return RATE_LIMIT_PREFIX + clientKey;
    }
}

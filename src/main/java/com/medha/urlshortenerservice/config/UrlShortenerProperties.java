package com.medha.urlshortenerservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Strongly-typed binding of the {@code app.*} configuration tree in
 * application.yml. Centralising these values here means every Redis-related
 * tunable (TTLs, rate limits, ID generator offset, flush cadence) is
 * discoverable in one place instead of being scattered as {@code @Value}
 * annotations throughout the service layer.
 */
@ConfigurationProperties(prefix = "app")
public class UrlShortenerProperties {

    /** Public base URL used to build fully-qualified short URLs in API responses. */
    private String baseUrl = "http://localhost:8080";

    @NestedConfigurationProperty
    private final Cache cache = new Cache();

    @NestedConfigurationProperty
    private final IdGenerator idGenerator = new IdGenerator();

    @NestedConfigurationProperty
    private final RateLimit rateLimit = new RateLimit();

    @NestedConfigurationProperty
    private final ClickTracking clickTracking = new ClickTracking();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Cache getCache() {
        return cache;
    }

    public IdGenerator getIdGenerator() {
        return idGenerator;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public ClickTracking getClickTracking() {
        return clickTracking;
    }

    public static class Cache {
        /** TTL, in seconds, for the cache-aside short-code -> URL entries in Redis. */
        private long urlTtlSeconds = 3600;

        public long getUrlTtlSeconds() {
            return urlTtlSeconds;
        }

        public void setUrlTtlSeconds(long urlTtlSeconds) {
            this.urlTtlSeconds = urlTtlSeconds;
        }
    }

    public static class IdGenerator {
        /** Redis key backing the atomic INCR sequence used to mint new short codes. */
        private String sequenceKey = "url:id:sequence";
        /** Added to every generated sequence value so early codes aren't trivially short. */
        private long offset = 100000;

        public String getSequenceKey() {
            return sequenceKey;
        }

        public void setSequenceKey(String sequenceKey) {
            this.sequenceKey = sequenceKey;
        }

        public long getOffset() {
            return offset;
        }

        public void setOffset(long offset) {
            this.offset = offset;
        }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private long maxRequests = 20;
        private long windowSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(long maxRequests) {
            this.maxRequests = maxRequests;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    public static class ClickTracking {
        private long flushIntervalMs = 15000;
        private int leaderboardSize = 10;

        public long getFlushIntervalMs() {
            return flushIntervalMs;
        }

        public void setFlushIntervalMs(long flushIntervalMs) {
            this.flushIntervalMs = flushIntervalMs;
        }

        public int getLeaderboardSize() {
            return leaderboardSize;
        }

        public void setLeaderboardSize(int leaderboardSize) {
            this.leaderboardSize = leaderboardSize;
        }
    }
}

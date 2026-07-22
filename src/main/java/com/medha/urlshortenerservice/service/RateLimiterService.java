package com.medha.urlshortenerservice.service;

import com.medha.urlshortenerservice.config.RedisKeys;
import com.medha.urlshortenerservice.config.UrlShortenerProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * A simple fixed-window rate limiter built directly on {@code INCR} +
 * {@code EXPIRE}: the first request in a window creates the counter and
 * sets its TTL; every subsequent request in the same window just
 * increments it. Once the counter exceeds the configured maximum, further
 * requests are rejected until the key naturally expires and the window
 * resets. Protects the short-URL creation endpoint from abuse.
 */
@Service
public class RateLimiterService {

    private final StringRedisTemplate redisTemplate;
    private final UrlShortenerProperties properties;

    public RateLimiterService(StringRedisTemplate redisTemplate, UrlShortenerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * @param clientKey a caller-identifying key (typically the client IP address)
     * @return true if the request is allowed, false if the caller has exceeded the limit
     */
    public boolean tryAcquire(String clientKey) {
        if (!properties.getRateLimit().isEnabled()) {
            return true;
        }
        String key = RedisKeys.rateLimitKey(clientKey);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(properties.getRateLimit().getWindowSeconds()));
        }
        return count == null || count <= properties.getRateLimit().getMaxRequests();
    }
}

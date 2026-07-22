package com.medha.urlshortenerservice.service;

import com.medha.urlshortenerservice.config.UrlShortenerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private UrlShortenerProperties properties;
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        properties = new UrlShortenerProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setMaxRequests(3);
        properties.getRateLimit().setWindowSeconds(60);
        rateLimiterService = new RateLimiterService(redisTemplate, properties);
    }

    @Test
    void tryAcquire_allowsFirstRequestAndSetsExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ratelimit:create:1.2.3.4")).thenReturn(1L);

        boolean allowed = rateLimiterService.tryAcquire("1.2.3.4");

        assertThat(allowed).isTrue();
        verify(redisTemplate).expire(eq("ratelimit:create:1.2.3.4"), any(Duration.class));
    }

    @Test
    void tryAcquire_allowsRequestsUnderLimitWithoutResettingExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ratelimit:create:1.2.3.4")).thenReturn(2L);

        boolean allowed = rateLimiterService.tryAcquire("1.2.3.4");

        assertThat(allowed).isTrue();
        verify(redisTemplate, never()).expire(eq("ratelimit:create:1.2.3.4"), any(Duration.class));
    }

    @Test
    void tryAcquire_blocksRequestsOverLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ratelimit:create:1.2.3.4")).thenReturn(4L);

        boolean allowed = rateLimiterService.tryAcquire("1.2.3.4");

        assertThat(allowed).isFalse();
    }

    @Test
    void tryAcquire_alwaysAllows_whenDisabled() {
        properties.getRateLimit().setEnabled(false);

        boolean allowed = rateLimiterService.tryAcquire("anyone");

        assertThat(allowed).isTrue();
    }
}

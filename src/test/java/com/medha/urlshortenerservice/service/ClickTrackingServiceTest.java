package com.medha.urlshortenerservice.service;

import com.medha.urlshortenerservice.config.UrlShortenerProperties;
import com.medha.urlshortenerservice.dto.PopularUrlResponse;
import com.medha.urlshortenerservice.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClickTrackingServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private UrlMappingRepository urlMappingRepository;

    private ClickTrackingService clickTrackingService;

    @BeforeEach
    void setUp() {
        UrlShortenerProperties properties = new UrlShortenerProperties();
        clickTrackingService = new ClickTrackingService(redisTemplate, urlMappingRepository, properties);
    }

    @Test
    void recordClick_incrementsCounterDirtySetAndLeaderboard() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        clickTrackingService.recordClick("abc123");

        verify(valueOperations).increment("clicks:pending:abc123");
        verify(setOperations).add("clicks:dirty-set", "abc123");
        verify(zSetOperations).incrementScore("urls:leaderboard", "abc123", 1);
    }

    @Test
    void getPendingClicks_returnsZero_whenNoPendingCounter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("clicks:pending:abc123")).thenReturn(null);

        assertThat(clickTrackingService.getPendingClicks("abc123")).isZero();
    }

    @Test
    void getPendingClicks_parsesStoredValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("clicks:pending:abc123")).thenReturn("7");

        assertThat(clickTrackingService.getPendingClicks("abc123")).isEqualTo(7L);
    }

    @Test
    void flushPendingClicks_appliesDeltaToDatabaseAndClearsRedisState() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(setOperations.members("clicks:dirty-set")).thenReturn(Set.of("abc123"));
        when(valueOperations.getAndDelete("clicks:pending:abc123")).thenReturn("5");
        when(urlMappingRepository.incrementClickCount("abc123", 5L)).thenReturn(1);

        clickTrackingService.flushPendingClicks();

        verify(setOperations).remove("clicks:dirty-set", "abc123");
        verify(urlMappingRepository).incrementClickCount("abc123", 5L);
    }

    @Test
    void flushPendingClicks_doesNothing_whenDirtySetIsEmpty() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("clicks:dirty-set")).thenReturn(Set.of());

        clickTrackingService.flushPendingClicks();

        verify(urlMappingRepository, never()).incrementClickCount(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void topPopular_noArg_usesConfiguredDefaultLeaderboardSize() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRangeWithScores("urls:leaderboard", 0, 9)).thenReturn(Set.of());

        List<PopularUrlResponse> result = clickTrackingService.topPopular();

        assertThat(result).isEmpty();
        verify(zSetOperations).reverseRangeWithScores("urls:leaderboard", 0, 9);
    }

    @Test
    void topPopular_mapsSortedSetIntoRankedResponses() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>("popular-code", 42.0));
        tuples.add(new DefaultTypedTuple<>("second-code", 10.0));
        when(zSetOperations.reverseRangeWithScores("urls:leaderboard", 0, 1)).thenReturn(tuples);

        List<PopularUrlResponse> result = clickTrackingService.topPopular(2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).rank()).isEqualTo(1);
        assertThat(result.get(0).shortCode()).isEqualTo("popular-code");
        assertThat(result.get(0).clicks()).isEqualTo(42L);
        assertThat(result.get(1).rank()).isEqualTo(2);
    }

    @Test
    void evictAll_removesEveryRelatedRedisArtifact() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        clickTrackingService.evictAll("abc123");

        verify(redisTemplate).delete("url:cache:abc123");
        verify(redisTemplate).delete("clicks:pending:abc123");
        verify(setOperations).remove("clicks:dirty-set", "abc123");
        verify(zSetOperations).remove("urls:leaderboard", "abc123");
    }
}

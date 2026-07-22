package com.medha.urlshortenerservice.service;

import com.medha.urlshortenerservice.config.UrlShortenerProperties;
import com.medha.urlshortenerservice.domain.UrlMapping;
import com.medha.urlshortenerservice.dto.CreateShortUrlRequest;
import com.medha.urlshortenerservice.dto.ShortUrlResponse;
import com.medha.urlshortenerservice.dto.UrlStatsResponse;
import com.medha.urlshortenerservice.exception.DuplicateAliasException;
import com.medha.urlshortenerservice.exception.UrlExpiredException;
import com.medha.urlshortenerservice.exception.UrlNotFoundException;
import com.medha.urlshortenerservice.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceImplTest {

    @Mock
    private UrlMappingRepository urlMappingRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private IdGeneratorService idGeneratorService;

    @Mock
    private ClickTrackingService clickTrackingService;

    private UrlShortenerServiceImpl service;

    @BeforeEach
    void setUp() {
        UrlShortenerProperties properties = new UrlShortenerProperties();
        properties.setBaseUrl("http://localhost:8080");
        properties.getCache().setUrlTtlSeconds(3600);

        service = new UrlShortenerServiceImpl(
                urlMappingRepository, redisTemplate, idGeneratorService, clickTrackingService, properties);
    }

    @Test
    void createShortUrl_generatesCodeAndPrimesCache_whenNoCustomAlias() {
        when(idGeneratorService.nextShortCode()).thenReturn("abc123");
        when(urlMappingRepository.existsByShortCode("abc123")).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com/very/long/path", null, null);

        ShortUrlResponse response = service.createShortUrl(request);

        assertThat(response.shortCode()).isEqualTo("abc123");
        assertThat(response.shortUrl()).isEqualTo("http://localhost:8080/abc123");
        assertThat(response.originalUrl()).isEqualTo(request.originalUrl());

        verify(urlMappingRepository).save(any(UrlMapping.class));
        verify(valueOperations).set(eq("url:cache:abc123"), eq(request.originalUrl()), any());
    }

    @Test
    void createShortUrl_usesCustomAlias_whenProvidedAndAvailable() {
        when(urlMappingRepository.existsByShortCode("my-alias")).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "my-alias", null);

        ShortUrlResponse response = service.createShortUrl(request);

        assertThat(response.shortCode()).isEqualTo("my-alias");
        verify(idGeneratorService, never()).nextShortCode();
    }

    @Test
    void createShortUrl_throwsDuplicateAlias_whenAliasTaken() {
        when(urlMappingRepository.existsByShortCode("taken")).thenReturn(true);

        CreateShortUrlRequest request = new CreateShortUrlRequest("https://example.com", "taken", null);

        assertThatThrownBy(() -> service.createShortUrl(request))
                .isInstanceOf(DuplicateAliasException.class);

        verify(urlMappingRepository, never()).save(any());
    }

    @Test
    void resolveAndRecordClick_returnsCachedValue_onCacheHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("url:cache:abc123")).thenReturn("https://example.com");

        String result = service.resolveAndRecordClick("abc123");

        assertThat(result).isEqualTo("https://example.com");
        verify(urlMappingRepository, never()).findByShortCode(anyString());
        verify(clickTrackingService).recordClick("abc123");
    }

    @Test
    void resolveAndRecordClick_fallsBackToDatabase_onCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("url:cache:abc123")).thenReturn(null);

        UrlMapping mapping = new UrlMapping("abc123", "https://example.com", false, null);
        when(urlMappingRepository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));

        String result = service.resolveAndRecordClick("abc123");

        assertThat(result).isEqualTo("https://example.com");
        verify(valueOperations).set(eq("url:cache:abc123"), eq("https://example.com"), any());
        verify(clickTrackingService).recordClick("abc123");
    }

    @Test
    void resolveAndRecordClick_throwsNotFound_whenMappingAbsent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("url:cache:missing")).thenReturn(null);
        when(urlMappingRepository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveAndRecordClick("missing"))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void resolveAndRecordClick_throwsExpired_whenMappingPastExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("url:cache:abc123")).thenReturn(null);

        UrlMapping expired = new UrlMapping("abc123", "https://example.com", false, Instant.now().minus(1, ChronoUnit.DAYS));
        when(urlMappingRepository.findByShortCode("abc123")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.resolveAndRecordClick("abc123"))
                .isInstanceOf(UrlExpiredException.class);

        verify(clickTrackingService, never()).recordClick(anyString());
    }

    @Test
    void getStats_combinesPersistedAndPendingClicks() {
        UrlMapping mapping = new UrlMapping("abc123", "https://example.com", false, null);
        mapping.addClicks(5);
        when(urlMappingRepository.findByShortCode("abc123")).thenReturn(Optional.of(mapping));
        when(clickTrackingService.getPendingClicks("abc123")).thenReturn(3L);

        UrlStatsResponse stats = service.getStats("abc123");

        assertThat(stats.totalClicks()).isEqualTo(8L);
        assertThat(stats.shortCode()).isEqualTo("abc123");
    }

    @Test
    void deleteShortUrl_deletesFromDbAndEvictsCache() {
        when(urlMappingRepository.existsByShortCode("abc123")).thenReturn(true);

        service.deleteShortUrl("abc123");

        verify(urlMappingRepository, times(1)).deleteByShortCode("abc123");
        verify(clickTrackingService).evictAll("abc123");
    }

    @Test
    void deleteShortUrl_throwsNotFound_whenMissing() {
        when(urlMappingRepository.existsByShortCode("missing")).thenReturn(false);

        assertThatThrownBy(() -> service.deleteShortUrl("missing"))
                .isInstanceOf(UrlNotFoundException.class);

        verify(urlMappingRepository, never()).deleteByShortCode(anyString());
    }
}

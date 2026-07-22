package com.medha.urlshortenerservice.service;

import com.medha.urlshortenerservice.config.RedisKeys;
import com.medha.urlshortenerservice.config.UrlShortenerProperties;
import com.medha.urlshortenerservice.domain.UrlMapping;
import com.medha.urlshortenerservice.dto.CreateShortUrlRequest;
import com.medha.urlshortenerservice.dto.ShortUrlResponse;
import com.medha.urlshortenerservice.dto.UrlStatsResponse;
import com.medha.urlshortenerservice.exception.DuplicateAliasException;
import com.medha.urlshortenerservice.exception.UrlExpiredException;
import com.medha.urlshortenerservice.exception.UrlNotFoundException;
import com.medha.urlshortenerservice.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Orchestrates the URL-shortener domain logic. MySQL (via
 * {@link UrlMappingRepository}) is the single source of truth; Redis is
 * consulted first on every read and kept in sync on every write, following
 * the classic cache-aside pattern.
 */
@Service
public class UrlShortenerServiceImpl implements UrlShortenerService {

    private static final Logger log = LoggerFactory.getLogger(UrlShortenerServiceImpl.class);
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    private final UrlMappingRepository urlMappingRepository;
    private final StringRedisTemplate redisTemplate;
    private final IdGeneratorService idGeneratorService;
    private final ClickTrackingService clickTrackingService;
    private final UrlShortenerProperties properties;

    public UrlShortenerServiceImpl(UrlMappingRepository urlMappingRepository,
                                    StringRedisTemplate redisTemplate,
                                    IdGeneratorService idGeneratorService,
                                    ClickTrackingService clickTrackingService,
                                    UrlShortenerProperties properties) {
        this.urlMappingRepository = urlMappingRepository;
        this.redisTemplate = redisTemplate;
        this.idGeneratorService = idGeneratorService;
        this.clickTrackingService = clickTrackingService;
        this.properties = properties;
    }

    @Override
    @Transactional
    public ShortUrlResponse createShortUrl(CreateShortUrlRequest request) {
        String shortCode;
        boolean isCustomAlias = request.customAlias() != null && !request.customAlias().isBlank();

        if (isCustomAlias) {
            shortCode = request.customAlias();
            if (urlMappingRepository.existsByShortCode(shortCode)) {
                throw new DuplicateAliasException(shortCode);
            }
        } else {
            shortCode = generateUniqueShortCode();
        }

        Instant expiresAt = request.expiresInDays() == null
                ? null
                : Instant.now().plus(request.expiresInDays(), ChronoUnit.DAYS);

        UrlMapping mapping = new UrlMapping(shortCode, request.originalUrl(), isCustomAlias, expiresAt);
        urlMappingRepository.save(mapping);

        // Write-through: prime the cache immediately so the very first redirect is a cache hit.
        cacheUrl(shortCode, request.originalUrl(), expiresAt);

        log.info("Created short URL '{}' -> '{}' (customAlias={})", shortCode, request.originalUrl(), isCustomAlias);
        return toShortUrlResponse(mapping);
    }

    @Override
    @Transactional(readOnly = true)
    public String resolveAndRecordClick(String shortCode) {
        String cached = redisTemplate.opsForValue().get(RedisKeys.urlCacheKey(shortCode));
        if (cached != null) {
            clickTrackingService.recordClick(shortCode);
            return cached;
        }

        UrlMapping mapping = urlMappingRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (mapping.isExpired()) {
            throw new UrlExpiredException(shortCode);
        }

        cacheUrl(shortCode, mapping.getOriginalUrl(), mapping.getExpiresAt());
        clickTrackingService.recordClick(shortCode);
        return mapping.getOriginalUrl();
    }

    @Override
    @Transactional(readOnly = true)
    public UrlStatsResponse getStats(String shortCode) {
        UrlMapping mapping = urlMappingRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        long pendingClicks = clickTrackingService.getPendingClicks(shortCode);
        long totalClicks = mapping.getClickCount() + pendingClicks;

        return new UrlStatsResponse(
                mapping.getShortCode(),
                buildShortUrl(mapping.getShortCode()),
                mapping.getOriginalUrl(),
                mapping.isCustomAlias(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt(),
                totalClicks
        );
    }

    @Override
    @Transactional
    public void deleteShortUrl(String shortCode) {
        if (!urlMappingRepository.existsByShortCode(shortCode)) {
            throw new UrlNotFoundException(shortCode);
        }
        urlMappingRepository.deleteByShortCode(shortCode);
        clickTrackingService.evictAll(shortCode);
        log.info("Deleted short URL '{}'", shortCode);
    }

    private String generateUniqueShortCode() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = idGeneratorService.nextShortCode();
            if (!urlMappingRepository.existsByShortCode(candidate)) {
                return candidate;
            }
            log.warn("Generated short code '{}' collided with an existing row, retrying (attempt {})", candidate, attempt + 1);
        }
        throw new IllegalStateException("Unable to generate a unique short code after " + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    private void cacheUrl(String shortCode, String originalUrl, Instant expiresAt) {
        Duration ttl = Duration.ofSeconds(properties.getCache().getUrlTtlSeconds());
        if (expiresAt != null) {
            Duration untilExpiry = Duration.between(Instant.now(), expiresAt);
            if (untilExpiry.isNegative() || untilExpiry.isZero()) {
                return; // already expired, don't cache
            }
            if (untilExpiry.compareTo(ttl) < 0) {
                ttl = untilExpiry;
            }
        }
        redisTemplate.opsForValue().set(RedisKeys.urlCacheKey(shortCode), originalUrl, ttl);
    }

    private ShortUrlResponse toShortUrlResponse(UrlMapping mapping) {
        return new ShortUrlResponse(
                mapping.getShortCode(),
                buildShortUrl(mapping.getShortCode()),
                mapping.getOriginalUrl(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt()
        );
    }

    private String buildShortUrl(String shortCode) {
        return properties.getBaseUrl() + "/" + shortCode;
    }
}

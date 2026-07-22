package com.medha.urlshortenerservice.controller;

import com.medha.urlshortenerservice.dto.CreateShortUrlRequest;
import com.medha.urlshortenerservice.dto.PopularUrlResponse;
import com.medha.urlshortenerservice.dto.ShortUrlResponse;
import com.medha.urlshortenerservice.dto.UrlStatsResponse;
import com.medha.urlshortenerservice.exception.RateLimitExceededException;
import com.medha.urlshortenerservice.service.ClickTrackingService;
import com.medha.urlshortenerservice.service.RateLimiterService;
import com.medha.urlshortenerservice.service.UrlShortenerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Management API for short URLs: create, inspect stats, delete, and view
 * the Redis-backed click leaderboard.
 */
@RestController
@RequestMapping("/api/urls")
public class UrlShortenerController {

    private final UrlShortenerService urlShortenerService;
    private final RateLimiterService rateLimiterService;
    private final ClickTrackingService clickTrackingService;

    public UrlShortenerController(UrlShortenerService urlShortenerService,
                                   RateLimiterService rateLimiterService,
                                   ClickTrackingService clickTrackingService) {
        this.urlShortenerService = urlShortenerService;
        this.rateLimiterService = rateLimiterService;
        this.clickTrackingService = clickTrackingService;
    }

    @PostMapping
    public ResponseEntity<ShortUrlResponse> createShortUrl(@Valid @RequestBody CreateShortUrlRequest request,
                                                             HttpServletRequest httpRequest) {
        String clientKey = resolveClientKey(httpRequest);
        if (!rateLimiterService.tryAcquire(clientKey)) {
            throw new RateLimitExceededException("Rate limit exceeded for creating short URLs. Please try again later.");
        }
        ShortUrlResponse response = urlShortenerService.createShortUrl(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<UrlStatsResponse> getStats(@PathVariable String shortCode) {
        return ResponseEntity.ok(urlShortenerService.getStats(shortCode));
    }

    @DeleteMapping("/{shortCode}")
    public ResponseEntity<Void> deleteShortUrl(@PathVariable String shortCode) {
        urlShortenerService.deleteShortUrl(shortCode);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/popular")
    public ResponseEntity<List<PopularUrlResponse>> getPopularUrls(
            @RequestParam(name = "limit", required = false) Integer limit) {
        List<PopularUrlResponse> popular = (limit == null)
                ? clickTrackingService.topPopular()
                : clickTrackingService.topPopular(limit);
        return ResponseEntity.ok(popular);
    }

    private String resolveClientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

package com.medha.urlshortenerservice.dto;

import java.time.Instant;

public record UrlStatsResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        boolean customAlias,
        Instant createdAt,
        Instant expiresAt,
        long totalClicks
) {
}

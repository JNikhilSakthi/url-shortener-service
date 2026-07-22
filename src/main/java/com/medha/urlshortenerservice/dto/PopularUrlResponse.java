package com.medha.urlshortenerservice.dto;

/**
 * A single entry in the Redis-backed click leaderboard (sorted set).
 */
public record PopularUrlResponse(
        int rank,
        String shortCode,
        long clicks
) {
}

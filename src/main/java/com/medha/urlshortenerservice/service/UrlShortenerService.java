package com.medha.urlshortenerservice.service;

import com.medha.urlshortenerservice.dto.CreateShortUrlRequest;
import com.medha.urlshortenerservice.dto.ShortUrlResponse;
import com.medha.urlshortenerservice.dto.UrlStatsResponse;

public interface UrlShortenerService {

    ShortUrlResponse createShortUrl(CreateShortUrlRequest request);

    /** Resolves a short code to its original URL for redirect purposes, recording a click. */
    String resolveAndRecordClick(String shortCode);

    UrlStatsResponse getStats(String shortCode);

    void deleteShortUrl(String shortCode);
}

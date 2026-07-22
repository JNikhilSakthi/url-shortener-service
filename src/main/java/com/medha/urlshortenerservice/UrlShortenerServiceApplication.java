package com.medha.urlshortenerservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the URL Shortener service.
 *
 * <p>Redis is the star of this project and is used for four distinct
 * patterns, all wired up in this codebase:</p>
 * <ol>
 *     <li><b>Cache-aside</b> - resolved short-code to original-URL lookups are cached
 *         as plain strings with a TTL so that redirects avoid a MySQL round trip.</li>
 *     <li><b>Atomic counters</b> - a Redis {@code INCR} sequence generates collision-free
 *         short codes, and per-code click counters are incremented atomically on every
 *         redirect before being periodically flushed into MySQL.</li>
 *     <li><b>Sets</b> - a "dirty" set tracks which short codes have pending click
 *         counts so the scheduled flush job never has to scan the whole keyspace.</li>
 *     <li><b>Sorted sets</b> - a click leaderboard (ZSET) powers a "most popular URLs"
 *         endpoint with O(log N) updates.</li>
 * </ol>
 * It is also used for a simple fixed-window rate limiter protecting the creation endpoint.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class UrlShortenerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerServiceApplication.class, args);
    }
}

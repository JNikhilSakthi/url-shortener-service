package com.medha.urlshortenerservice.service;

import com.medha.urlshortenerservice.config.UrlShortenerProperties;
import com.medha.urlshortenerservice.util.Base62Encoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Generates collision-free short codes using a single Redis {@code INCR}
 * counter as a distributed sequence generator.
 *
 * <p>Why Redis and not a MySQL auto-increment column? {@code INCR} is O(1),
 * atomic without any locking, and works identically whether the app is
 * running as one instance or scaled out to many - every instance shares the
 * same counter in Redis, so no two instances can ever mint the same code.</p>
 */
@Service
public class IdGeneratorService {

    private final StringRedisTemplate redisTemplate;
    private final UrlShortenerProperties properties;

    public IdGeneratorService(StringRedisTemplate redisTemplate, UrlShortenerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * Atomically increments the shared Redis sequence and encodes the
     * result as a base-62 string. An offset is added so that codes are
     * never trivially short (e.g. "1", "2") even right after the sequence
     * is first created.
     */
    public String nextShortCode() {
        Long sequenceValue = redisTemplate.opsForValue()
                .increment(properties.getIdGenerator().getSequenceKey());
        long value = (sequenceValue == null ? 1L : sequenceValue) + properties.getIdGenerator().getOffset();
        return Base62Encoder.encode(value);
    }
}

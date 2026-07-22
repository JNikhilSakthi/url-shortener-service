package com.medha.urlshortenerservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/**
 * Request payload for POST /api/urls.
 *
 * @param originalUrl    the long URL to shorten (required, must be http/https)
 * @param customAlias    optional caller-chosen short code (3-20 alphanumeric/hyphen/underscore chars)
 * @param expiresInDays  optional TTL in days after which the short URL stops resolving (1-365)
 */
public record CreateShortUrlRequest(

        @NotBlank(message = "originalUrl must not be blank")
        @Size(max = 2048, message = "originalUrl must be at most 2048 characters")
        @URL(regexp = "^https?://.+", message = "originalUrl must be a well-formed http(s) URL")
        String originalUrl,

        @Pattern(regexp = "^[a-zA-Z0-9_-]{3,20}$", message = "customAlias must be 3-20 alphanumeric, hyphen or underscore characters")
        String customAlias,

        @Min(value = 1, message = "expiresInDays must be at least 1")
        @Max(value = 365, message = "expiresInDays must be at most 365")
        Integer expiresInDays
) {
}

package com.medha.urlshortenerservice.exception;

public class UrlExpiredException extends RuntimeException {

    public UrlExpiredException(String shortCode) {
        super("Short URL '" + shortCode + "' has expired");
    }
}

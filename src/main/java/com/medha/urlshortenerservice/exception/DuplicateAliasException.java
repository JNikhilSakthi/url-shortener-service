package com.medha.urlshortenerservice.exception;

public class DuplicateAliasException extends RuntimeException {

    public DuplicateAliasException(String alias) {
        super("Custom alias '" + alias + "' is already taken");
    }
}

package com.medha.urlshortenerservice.util;

/**
 * Encodes non-negative longs into a compact base-62 [0-9a-zA-Z] string and
 * back. Used to turn the monotonically increasing value produced by the
 * Redis {@code INCR}-based {@code IdGeneratorService} into a short,
 * URL-safe code.
 */
public final class Base62Encoder {

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length();

    private Base62Encoder() {
    }

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Cannot base62-encode a negative value: " + value);
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }
        StringBuilder sb = new StringBuilder();
        long remaining = value;
        while (remaining > 0) {
            int digit = (int) (remaining % BASE);
            sb.append(ALPHABET.charAt(digit));
            remaining /= BASE;
        }
        return sb.reverse().toString();
    }

    public static long decode(String code) {
        long result = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = ALPHABET.indexOf(code.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid base62 character in code: " + code);
            }
            result = result * BASE + digit;
        }
        return result;
    }
}

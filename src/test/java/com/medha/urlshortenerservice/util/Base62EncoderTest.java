package com.medha.urlshortenerservice.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62EncoderTest {

    @Test
    void encode_zero_returnsFirstAlphabetCharacter() {
        assertThat(Base62Encoder.encode(0)).isEqualTo("0");
    }

    @Test
    void encode_rejectsNegativeValues() {
        assertThatThrownBy(() -> Base62Encoder.encode(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 61L, 62L, 100000L, 123456789L, Long.MAX_VALUE})
    void encodeThenDecode_isIdentity(long value) {
        String encoded = Base62Encoder.encode(value);
        assertThat(Base62Encoder.decode(encoded)).isEqualTo(value);
    }

    @Test
    void encode_isMonotonicallyNonDecreasingInLength() {
        String small = Base62Encoder.encode(100000L);
        String bigger = Base62Encoder.encode(100000L + 62L * 62L * 62L);
        assertThat(bigger.length()).isGreaterThanOrEqualTo(small.length());
    }

    @Test
    void decode_rejectsInvalidCharacters() {
        assertThatThrownBy(() -> Base62Encoder.decode("abc!"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

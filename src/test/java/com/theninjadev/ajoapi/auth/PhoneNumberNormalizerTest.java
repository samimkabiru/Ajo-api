package com.theninjadev.ajoapi.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhoneNumberNormalizerTest {

    private final PhoneNumberNormalizer normalizer = new PhoneNumberNormalizer();

    @ParameterizedTest
    @CsvSource({
            "08012345678, +2348012345678",
            "07012345678, +2347012345678",
            "09012345678, +2349012345678",
            "2348012345678, +2348012345678",
            "+2348012345678, +2348012345678",
            "'0801 234 5678', +2348012345678",
            "'+234 801 234 5678', +2348012345678",
            "'234 801 234 5678', +2348012345678",
            "0803-123-4567, +2348031234567",
            "'(0803) 123 4567', +2348031234567",
            "0803.123.4567, +2348031234567"
    })
    void normalizesValidInputsToE164(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0612345678",
            "081234567",
            "080123456789",
            "+15551234567",
            "abc123",
            "+2340012345678",
            "234012345678"
    })
    void rejectsInvalidInputs(String input) {
        assertThrows(InvalidPhoneNumberException.class, () -> normalizer.normalize(input));
    }

    @Test
    void rejectsNull() {
        assertThrows(InvalidPhoneNumberException.class, () -> normalizer.normalize(null));
    }

    @Test
    void rejectsBlank() {
        assertThrows(InvalidPhoneNumberException.class, () -> normalizer.normalize(""));
    }
}

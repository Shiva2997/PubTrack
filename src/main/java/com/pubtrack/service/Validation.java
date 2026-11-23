package com.pubtrack.service;

import com.pubtrack.exception.ValidationException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Input checks shared by the service methods. All failures become HTTP 400 with a readable message. */
final class Validation {

    private Validation() {
    }

    static String text(String field, String value, int min, int max) {
        String v = value == null ? "" : value.strip();
        if (v.length() < min) {
            throw new ValidationException(field + " must be at least " + min + " characters");
        }
        if (v.length() > max) {
            throw new ValidationException(field + " must be at most " + max + " characters");
        }
        return v;
    }

    /** Returns null for blank input so optional columns stay NULL rather than "". */
    static String optionalText(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return text(field, value, 1, max);
    }

    static <E extends Enum<E>> E enumValue(String field, String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " is required");
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String allowed = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
            throw new ValidationException(field + " must be one of: " + allowed);
        }
    }
}

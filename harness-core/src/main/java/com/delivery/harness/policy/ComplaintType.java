package com.delivery.harness.policy;

import java.util.Locale;
import java.util.Optional;

public enum ComplaintType {
    OVERTIME,
    WRONG_ORDER,
    DAMAGED,
    MISSING_ITEM;

    public static Optional<ComplaintType> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ComplaintType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

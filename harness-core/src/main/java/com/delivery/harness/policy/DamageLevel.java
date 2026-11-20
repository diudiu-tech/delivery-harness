package com.delivery.harness.policy;

import java.util.Locale;
import java.util.Optional;

public enum DamageLevel {
    FULL,
    PARTIAL;

    public static Optional<DamageLevel> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DamageLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

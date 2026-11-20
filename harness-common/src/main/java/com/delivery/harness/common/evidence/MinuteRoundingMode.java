package com.delivery.harness.common.evidence;

import java.time.Duration;
import java.util.Objects;

/**
 * Explicit minute conversion semantics for non-negative durations so policy
 * boundaries (such as 15m59s vs 16m00s) are never left implicit.
 */
public enum MinuteRoundingMode {
    FLOOR_TRUNCATE,
    CEIL,
    ROUND_HALF_UP;

    public long toMinutes(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative: " + duration);
        }
        long seconds = duration.getSeconds();
        int nanos = duration.getNano();
        long wholeMinutes = seconds / 60L;
        long remSeconds = seconds % 60L;
        return switch (this) {
            case FLOOR_TRUNCATE -> wholeMinutes;
            case CEIL -> (remSeconds > 0L || nanos > 0) ? wholeMinutes + 1L : wholeMinutes;
            case ROUND_HALF_UP -> (remSeconds > 30L || (remSeconds == 30L && nanos >= 0))
                    ? wholeMinutes + 1L : wholeMinutes;
        };
    }
}

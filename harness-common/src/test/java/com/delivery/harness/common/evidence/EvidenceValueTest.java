package com.delivery.harness.common.evidence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceValueTest {

    private static final Instant T0 = Instant.parse("2026-09-01T04:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T04:15:00Z");

    @Test
    void foldsKnownUnknownAndInvalidExhaustively() {
        EvidenceValue<Long> known = EvidenceValue.known(18L, "order_timeline", T1, T0);
        EvidenceValue<Long> unknown = EvidenceValue.unknown("MISSING_FIELD", "deliveryTime is null");
        EvidenceValue<Long> invalid = EvidenceValue.invalid("-5", "deliveryTime < pickupTime");

        assertAll(
                () -> assertTrue(known.isKnown()),
                () -> assertFalse(known.isUnknown()),
                () -> assertFalse(known.isInvalid()),
                () -> assertEquals(Optional.of(18L), known.knownValue()),
                () -> assertEquals("known:18", labelOf(known)),

                () -> assertTrue(unknown.isUnknown()),
                () -> assertEquals(Optional.empty(), unknown.knownValue()),
                () -> assertEquals("unknown:MISSING_FIELD", labelOf(unknown)),

                () -> assertTrue(invalid.isInvalid()),
                () -> assertEquals(Optional.empty(), invalid.knownValue()),
                () -> assertEquals("invalid:deliveryTime < pickupTime", labelOf(invalid)));
    }

    @Test
    void knownDefaultsAsOfToObservedAtWhenOmitted() {
        EvidenceValue.Known<String> value = EvidenceValue.known("DELIVERED", "order_query", T1);
        assertEquals(T1, value.observedAt());
        assertEquals(T1, value.asOf());
    }

    @Test
    void minuteRoundingModesHandleBoundarySecondsExplicitly() {
        Duration fifteenFiftyNine = Duration.ofMinutes(15).plusSeconds(59);
        Duration fifteenThirty = Duration.ofMinutes(15).plusSeconds(30);
        Duration fifteenTwentyNine = Duration.ofMinutes(15).plusSeconds(29);
        Duration exactFifteen = Duration.ofMinutes(15);

        assertAll(
                () -> assertEquals(15L, MinuteRoundingMode.FLOOR_TRUNCATE.toMinutes(fifteenFiftyNine)),
                () -> assertEquals(16L, MinuteRoundingMode.CEIL.toMinutes(fifteenFiftyNine)),
                () -> assertEquals(15L, MinuteRoundingMode.CEIL.toMinutes(exactFifteen)),
                () -> assertEquals(16L, MinuteRoundingMode.ROUND_HALF_UP.toMinutes(fifteenThirty)),
                () -> assertEquals(15L, MinuteRoundingMode.ROUND_HALF_UP.toMinutes(fifteenTwentyNine)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> MinuteRoundingMode.FLOOR_TRUNCATE.toMinutes(Duration.ofSeconds(-1))));
    }

    private static String labelOf(EvidenceValue<Long> value) {
        return value.fold(
                k -> "known:" + k.value(),
                u -> "unknown:" + u.reasonCode(),
                i -> "invalid:" + i.violation());
    }
}

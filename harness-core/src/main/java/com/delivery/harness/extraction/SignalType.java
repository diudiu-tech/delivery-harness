package com.delivery.harness.extraction;

import java.util.Locale;
import java.util.Optional;

/**
 * Closed set of unconfirmed conflict signals extractable from unstructured
 * conversation text.
 */
public enum SignalType {
    CUSTOMER_CHANGED_ADDRESS(true),
    MERCHANT_ITEM_SWAP(false),
    DAMAGE_CLAIM(false),
    RIDER_ACKNOWLEDGED_DELAY(false);

    private final boolean exemptionOrReductionSensitive;

    SignalType(boolean exemptionOrReductionSensitive) {
        this.exemptionOrReductionSensitive = exemptionOrReductionSensitive;
    }

    /**
     * True for signals that, if misused, could reduce user compensation or
     * improperly exempt a party; false positives here are a hard-gate veto in
     * evaluation.
     */
    public boolean isExemptionOrReductionSensitive() {
        return exemptionOrReductionSensitive;
    }

    public static Optional<SignalType> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(SignalType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

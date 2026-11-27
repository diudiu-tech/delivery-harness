package com.delivery.harness.policy;

/**
 * Explicit adjudication outcomes so zero-payout and non-actionable cases are
 * never conflated.
 */
public enum AdjudicationStatus {
    /** Applicable policy matched, all required evidence valid, payout within escalation cap. */
    ELIGIBLE,
    /** Applicable policy matched and affirmatively decided zero payout (e.g. delivered on time). */
    INELIGIBLE,
    /** Required evidence is missing, invalid, or stale; no actionable amount may be displayed. */
    NEEDS_EVIDENCE,
    /** Calculated payout exceeds the bundle's escalation cap; no directly actionable amount. */
    EXCEEDS_CAP_ESCALATE,
    /** No active rule covers this complaint type or snapshot evaluation time. */
    NO_APPLICABLE_POLICY,
    /** Order is outside supported scope (e.g. order status is not DELIVERED). */
    OUT_OF_SCOPE
}

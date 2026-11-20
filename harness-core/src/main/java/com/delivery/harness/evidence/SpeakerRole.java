package com.delivery.harness.evidence;

/**
 * Authoritative message sender role sourced strictly from conversation
 * metadata, never inferred by an LLM.
 */
public enum SpeakerRole {
    CUSTOMER,
    RIDER,
    MERCHANT,
    SYSTEM
}

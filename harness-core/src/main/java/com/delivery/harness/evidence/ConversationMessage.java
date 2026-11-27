package com.delivery.harness.evidence;

import java.time.Instant;
import java.util.Objects;

/**
 * Single unstructured conversation entry anchored by an immutable evidence ID
 * and metadata-sourced {@link SpeakerRole}.
 */
public record ConversationMessage(
        String evidenceId,
        SpeakerRole speakerRole,
        Instant sentAt,
        String text) {

    public ConversationMessage {
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(speakerRole, "speakerRole must not be null");
        Objects.requireNonNull(sentAt, "sentAt must not be null");
        Objects.requireNonNull(text, "text must not be null");
    }
}

package com.delivery.harness.extraction;

import com.delivery.harness.evidence.SpeakerRole;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verified but <em>unconfirmed</em> signal extracted from unstructured text.
 *
 * <p>{@code speakerRole} is sourced strictly from {@code ConversationMessage}
 * metadata, never from LLM output. This type is intentionally outside
 * {@code com.delivery.harness.policy} and never enters {@code SingleSourcePolicyEngine}.
 */
public record ClaimedFact(
        SignalType signalType,
        SpeakerRole speakerRole,
        String sourceEvidenceId,
        int startOffset,
        int endOffset,
        String quote,
        String extractor,
        String promptHash,
        String statement) {

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("signal_type", signalType.name());
        map.put("speaker_role", speakerRole.name());
        map.put("source_evidence_id", sourceEvidenceId);
        map.put("start_offset", startOffset);
        map.put("end_offset", endOffset);
        map.put("quote", quote);
        map.put("extractor", extractor);
        if (promptHash != null) {
            map.put("prompt_hash", promptHash);
        }
        if (statement != null && !statement.isBlank()) {
            map.put("statement", statement);
        }
        map.put("confirmed", false);
        return map;
    }
}

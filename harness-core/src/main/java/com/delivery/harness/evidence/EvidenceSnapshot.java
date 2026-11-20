package com.delivery.harness.evidence;

import com.delivery.harness.common.evidence.EvidenceType;
import com.delivery.harness.common.evidence.EvidenceValue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable closed evidence ledger for one order at one point in time.
 *
 * <p>Every fact is addressed by a stable {@code EV-*} identifier, typed as a
 * three-valued {@link EvidenceValue}, and sealed under a canonical SHA-256
 * {@link #contentHash()}. No evaluation labels or scenario hints are allowed
 * inside a snapshot.
 */
public final class EvidenceSnapshot {

    private final String snapshotId;
    private final String orderId;
    private final ZoneId zoneId;
    private final Instant capturedAt;
    private final Instant asOf;
    private final String sourceMode;
    private final Map<String, EvidenceItem> items;
    private final List<ConversationMessage> messages;
    private final String contentHash;

    private EvidenceSnapshot(
            String snapshotId,
            String orderId,
            ZoneId zoneId,
            Instant capturedAt,
            Instant asOf,
            String sourceMode,
            Map<String, EvidenceItem> items,
            List<ConversationMessage> messages,
            String contentHash) {
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId must not be null");
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt must not be null");
        this.asOf = Objects.requireNonNull(asOf, "asOf must not be null");
        this.sourceMode = Objects.requireNonNull(sourceMode, "sourceMode must not be null");
        this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash must not be null");
    }

    public static EvidenceSnapshot create(
            String orderId,
            ZoneId zoneId,
            Instant capturedAt,
            Instant asOf,
            String sourceMode,
            List<EvidenceItem> items,
            List<ConversationMessage> messages) {
        Map<String, EvidenceItem> byId = new LinkedHashMap<>();
        if (items != null) {
            for (EvidenceItem item : items) {
                if (byId.containsKey(item.evidenceId())) {
                    throw new IllegalArgumentException("Duplicate evidenceId: " + item.evidenceId());
                }
                byId.put(item.evidenceId(), item);
            }
        }
        List<ConversationMessage> msgList = messages == null ? List.of() : List.copyOf(messages);
        for (ConversationMessage msg : msgList) {
            if (!byId.containsKey(msg.evidenceId())) {
                byId.put(msg.evidenceId(), new EvidenceItem(
                        msg.evidenceId(),
                        EvidenceType.IM_MESSAGE,
                        EvidenceValue.known(msg.text(), "im:" + msg.speakerRole().name(), msg.sentAt(), asOf)));
            }
        }

        String hash = computeHash(orderId, zoneId, asOf, sourceMode, byId, msgList);
        String snapshotId = "SNAP-" + hash.substring(0, 16);
        return new EvidenceSnapshot(
                snapshotId, orderId, zoneId, capturedAt, asOf, sourceMode, byId, msgList, hash);
    }

    /**
     * Returns a new immutable snapshot with one human-attested {@code EV-HUMAN-*}
     * evidence entry appended and its canonical SHA-256 recomputed.
     */
    public EvidenceSnapshot withHumanAttestation(
            String evidenceId,
            EvidenceType type,
            EvidenceValue.Known<?> attestedValue,
            Instant attestedAt) {
        if (evidenceId == null || !evidenceId.startsWith("EV-HUMAN-")) {
            throw new IllegalArgumentException("Human attestation ID must start with EV-HUMAN-");
        }
        List<EvidenceItem> updated = new ArrayList<>(items.values());
        updated.add(new EvidenceItem(evidenceId, type, attestedValue));
        return create(orderId, zoneId, attestedAt, asOf, sourceMode, updated, messages);
    }

    private static String computeHash(
            String orderId,
            ZoneId zoneId,
            Instant asOf,
            String sourceMode,
            Map<String, EvidenceItem> items,
            List<ConversationMessage> messages) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("orderId", orderId);
        payload.put("zoneId", zoneId);
        payload.put("asOf", asOf);
        payload.put("sourceMode", sourceMode);
        payload.put("items", new TreeMap<>(items));
        payload.put("messages", messages);
        return CanonicalJson.sha256(CanonicalJson.serialize(payload, zoneId));
    }

    public String snapshotId() {
        return snapshotId;
    }

    public String orderId() {
        return orderId;
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public Instant asOf() {
        return asOf;
    }

    public String sourceMode() {
        return sourceMode;
    }

    public Map<String, EvidenceItem> items() {
        return items;
    }

    public List<ConversationMessage> messages() {
        return messages;
    }

    public String contentHash() {
        return contentHash;
    }

    public boolean containsEvidenceId(String evidenceId) {
        return evidenceId != null && items.containsKey(evidenceId);
    }

    public Optional<EvidenceItem> findById(String evidenceId) {
        return Optional.ofNullable(items.get(evidenceId));
    }

    public Optional<ConversationMessage> findMessageById(String evidenceId) {
        if (evidenceId == null) {
            return Optional.empty();
        }
        return messages.stream().filter(m -> evidenceId.equals(m.evidenceId())).findFirst();
    }

    /**
     * Returns the latest item for a given {@link EvidenceType}, letting later
     * human attestations ({@code EV-HUMAN-*}) supersede initial {@code Unknown}
     * entries without mutating earlier records.
     */
    public Optional<EvidenceItem> latestByType(EvidenceType type) {
        EvidenceItem latest = null;
        for (EvidenceItem item : items.values()) {
            if (item.type() == type) {
                latest = item;
            }
        }
        return Optional.ofNullable(latest);
    }

    /** Flat type-indexed view for policy evaluation (latest item per type wins). */
    public Map<EvidenceType, EvidenceValue<?>> latestValuesByType() {
        Map<EvidenceType, EvidenceValue<?>> map = new EnumMap<>(EvidenceType.class);
        for (EvidenceItem item : items.values()) {
            map.put(item.type(), item.value());
        }
        return Collections.unmodifiableMap(map);
    }
}

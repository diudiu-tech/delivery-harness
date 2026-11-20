package com.delivery.harness.evidence;

import com.delivery.harness.common.evidence.EvidenceValue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic canonical JSON serialization and SHA-256 hashing for immutable
 * evidence snapshots.
 *
 * <p>Guarantees stable hashes regardless of map iteration order:
 * <ul>
 *   <li>all map keys are sorted lexicographically</li>
 *   <li>{@link BigDecimal} values use {@link BigDecimal#toPlainString()}</li>
 *   <li>{@link Instant} timestamps serialize as ISO-8601 with explicit offset in
 *       the snapshot's {@link ZoneId}</li>
 *   <li>{@link EvidenceValue} variants emit an explicit discriminator tag</li>
 * </ul>
 */
public final class CanonicalJson {

    private static final DateTimeFormatter OFFSET_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private CanonicalJson() {}

    public static String serialize(Object value, ZoneId zoneId) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, zoneId);
        return sb.toString();
    }

    public static String sha256(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value, ZoneId zoneId) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String s) {
            writeQuotedString(sb, s);
            return;
        }
        if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
            return;
        }
        if (value instanceof BigDecimal decimal) {
            writeQuotedString(sb, decimal.toPlainString());
            return;
        }
        if (value instanceof Number n) {
            sb.append(n);
            return;
        }
        if (value instanceof Instant instant) {
            writeQuotedString(sb, OFFSET_FORMATTER.format(instant.atZone(zoneId)));
            return;
        }
        if (value instanceof ZoneId z) {
            writeQuotedString(sb, z.getId());
            return;
        }
        if (value instanceof Enum<?> e) {
            writeQuotedString(sb, e.name());
            return;
        }
        if (value instanceof EvidenceValue<?> ev) {
            Map<String, Object> encoded = new TreeMap<>();
            ev.fold(
                    known -> {
                        encoded.put("state", "KNOWN");
                        encoded.put("value", known.value());
                        encoded.put("source", known.source());
                        encoded.put("observedAt", known.observedAt());
                        encoded.put("asOf", known.asOf());
                        return null;
                    },
                    unknown -> {
                        encoded.put("state", "UNKNOWN");
                        encoded.put("reasonCode", unknown.reasonCode());
                        encoded.put("detail", unknown.detail());
                        return null;
                    },
                    invalid -> {
                        encoded.put("state", "INVALID");
                        encoded.put("rawValue", invalid.rawValue());
                        encoded.put("violation", invalid.violation());
                        return null;
                    });
            writeMap(sb, encoded, zoneId);
            return;
        }
        if (value instanceof EvidenceItem item) {
            Map<String, Object> encoded = new TreeMap<>();
            encoded.put("evidenceId", item.evidenceId());
            encoded.put("type", item.type());
            encoded.put("value", item.value());
            writeMap(sb, encoded, zoneId);
            return;
        }
        if (value instanceof ConversationMessage msg) {
            Map<String, Object> encoded = new TreeMap<>();
            encoded.put("evidenceId", msg.evidenceId());
            encoded.put("speakerRole", msg.speakerRole());
            encoded.put("sentAt", msg.sentAt());
            encoded.put("text", msg.text());
            writeMap(sb, encoded, zoneId);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            writeMap(sb, sorted, zoneId);
            return;
        }
        if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, list.get(i), zoneId);
            }
            sb.append(']');
            return;
        }
        writeQuotedString(sb, value.toString());
    }

    private static void writeMap(StringBuilder sb, Map<String, Object> sorted, ZoneId zoneId) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeQuotedString(sb, entry.getKey());
            sb.append(':');
            writeValue(sb, entry.getValue(), zoneId);
        }
        sb.append('}');
    }

    private static void writeQuotedString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}

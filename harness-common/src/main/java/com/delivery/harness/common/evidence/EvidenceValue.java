package com.delivery.harness.common.evidence;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Three-valued representation of a single fact or derived measurement.
 *
 * <p>Java 17 does not have finalized exhaustive {@code switch} pattern
 * matching, so {@link #fold(Function, Function, Function)} enforces
 * compile-time exhaustiveness across all three states:
 * <ul>
 *   <li>{@link Known}: a valid measured value with provenance timestamps</li>
 *   <li>{@link Unknown}: absent data that must never be silently coerced to zero</li>
 *   <li>{@link Invalid}: corrupted or contradictory raw data (e.g. reversed timestamps)</li>
 * </ul>
 *
 * <p>Staleness is deliberately <em>not</em> stored on {@link Known}: whether a
 * measurement is stale depends on the active policy's staleness tolerance, so
 * keeping only {@code observedAt} and {@code asOf} preserves snapshot
 * immutability and hash stability across policy changes.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "state")
@JsonSubTypes({
    @JsonSubTypes.Type(value = EvidenceValue.Known.class, name = "KNOWN"),
    @JsonSubTypes.Type(value = EvidenceValue.Unknown.class, name = "UNKNOWN"),
    @JsonSubTypes.Type(value = EvidenceValue.Invalid.class, name = "INVALID")
})
public sealed interface EvidenceValue<T>
        permits EvidenceValue.Known, EvidenceValue.Unknown, EvidenceValue.Invalid {

    <R> R fold(
            Function<Known<T>, R> onKnown,
            Function<Unknown<T>, R> onUnknown,
            Function<Invalid<T>, R> onInvalid);

    default boolean isKnown() {
        return fold(k -> true, u -> false, i -> false);
    }

    default boolean isUnknown() {
        return fold(k -> false, u -> true, i -> false);
    }

    default boolean isInvalid() {
        return fold(k -> false, u -> false, i -> true);
    }

    default Optional<T> knownValue() {
        return fold(k -> Optional.of(k.value()), u -> Optional.empty(), i -> Optional.empty());
    }

    static <T> Known<T> known(T value, String source, Instant observedAt, Instant asOf) {
        return new Known<>(value, source, observedAt, asOf);
    }

    static <T> Known<T> known(T value, String source, Instant observedAt) {
        return new Known<>(value, source, observedAt, observedAt);
    }

    static <T> Unknown<T> unknown(String reasonCode, String detail) {
        return new Unknown<>(reasonCode, detail);
    }

    static <T> Invalid<T> invalid(String rawValue, String violation) {
        return new Invalid<>(rawValue, violation);
    }

    record Known<T>(T value, String source, Instant observedAt, Instant asOf) implements EvidenceValue<T> {
        public Known {
            Objects.requireNonNull(value, "value must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(observedAt, "observedAt must not be null");
            Objects.requireNonNull(asOf, "asOf must not be null");
        }

        @Override
        public <R> R fold(
                Function<Known<T>, R> onKnown,
                Function<Unknown<T>, R> onUnknown,
                Function<Invalid<T>, R> onInvalid) {
            return onKnown.apply(this);
        }
    }

    record Unknown<T>(String reasonCode, String detail) implements EvidenceValue<T> {
        public Unknown {
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
        }

        @Override
        public <R> R fold(
                Function<Known<T>, R> onKnown,
                Function<Unknown<T>, R> onUnknown,
                Function<Invalid<T>, R> onInvalid) {
            return onUnknown.apply(this);
        }
    }

    record Invalid<T>(String rawValue, String violation) implements EvidenceValue<T> {
        public Invalid {
            Objects.requireNonNull(rawValue, "rawValue must not be null");
            Objects.requireNonNull(violation, "violation must not be null");
        }

        @Override
        public <R> R fold(
                Function<Known<T>, R> onKnown,
                Function<Unknown<T>, R> onUnknown,
                Function<Invalid<T>, R> onInvalid) {
            return onInvalid.apply(this);
        }
    }
}

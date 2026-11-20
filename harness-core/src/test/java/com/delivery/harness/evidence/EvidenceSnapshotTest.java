package com.delivery.harness.evidence;

import com.delivery.harness.common.evidence.EvidenceType;
import com.delivery.harness.common.evidence.EvidenceValue;
import com.delivery.harness.policy.DamageLevel;
import com.delivery.harness.policy.PolicyBundleLoader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceSnapshotTest {

    private final SyntheticEvidenceSource source = new SyntheticEvidenceSource(PolicyBundleLoader.loadDefault());

    @Test
    void producesIdenticalCanonicalSha256AcrossRepeatedCollections() {
        EvidenceSnapshot first = source.collect("ORD-MERCHANT-01").orElseThrow();
        EvidenceSnapshot second = source.collect("ORD-MERCHANT-01").orElseThrow();

        assertAll(
                () -> assertEquals(64, first.contentHash().length()),
                () -> assertEquals(first.contentHash(), second.contentHash()),
                () -> assertEquals(first.snapshotId(), second.snapshotId()),
                () -> assertEquals(ZoneId.of("Asia/Shanghai"), first.zoneId()),
                () -> assertEquals("SYNTHETIC", first.sourceMode()));
    }

    @Test
    void canonicalJsonNormalizesKeyOrderAndBigDecimalPlainString() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("z", new BigDecimal("20.00"));
        a.put("a", Instant.parse("2026-09-01T04:00:00Z"));

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", Instant.parse("2026-09-01T04:00:00Z"));
        b.put("z", new BigDecimal("20.00"));

        String jsonA = CanonicalJson.serialize(a, ZoneId.of("Asia/Shanghai"));
        String jsonB = CanonicalJson.serialize(b, ZoneId.of("Asia/Shanghai"));

        assertAll(
                () -> assertEquals(jsonA, jsonB),
                () -> assertTrue(jsonA.contains("\"2026-09-01T12:00:00+08:00\"")),
                () -> assertTrue(jsonA.contains("\"20.00\"")),
                () -> assertEquals(CanonicalJson.sha256(jsonA), CanonicalJson.sha256(jsonB)));
    }

    @Test
    void returnsEmptyForUnknownOrdersInsteadOfFabricating() {
        assertAll(
                () -> assertEquals(Optional.empty(), source.collect("ORD-DOES-NOT-EXIST")),
                () -> assertEquals(Optional.empty(), source.collect("   ")),
                () -> assertEquals(Optional.empty(), source.collect(null)));
    }

    @Test
    void neverLeaksScenarioKeysOrGroundTruthLabelsIntoAnyFixtureSnapshot() {
        assertTrue(source.listAvailableFixtures().size() >= 12);
        for (EvidenceSource.FixtureSummary fixture : source.listAvailableFixtures()) {
            EvidenceSnapshot snapshot = source.collect(fixture.orderId()).orElseThrow();
            String serialized = CanonicalJson.serialize(snapshot.items(), snapshot.zoneId());
            assertAll(fixture.orderId(),
                    () -> assertFalse(serialized.contains("scenario_key")),
                    () -> assertFalse(serialized.contains("rootCauseHint")),
                    () -> assertFalse(serialized.contains("MERCHANT_SLOW")),
                    () -> assertFalse(serialized.contains("CAPACITY_SHORT")),
                    () -> assertFalse(serialized.contains("WEATHER_DELAY")),
                    () -> assertFalse(serialized.contains("RIDER_DETOUR")));
        }
    }

    @Test
    void preservesThreeValuedMissingAndCorruptedFixturesAndImMetadata() {
        EvidenceSnapshot missing = source.collect("ORD-MISSING-TS-01").orElseThrow();
        EvidenceSnapshot corrupt = source.collect("ORD-CORRUPT-TS-01").orElseThrow();
        EvidenceSnapshot imAddr = source.collect("ORD-IM-ADDR-01").orElseThrow();
        EvidenceSnapshot imAdv = source.collect("ORD-IM-NEG-INJ-01").orElseThrow();

        assertAll(
                () -> assertTrue(missing.latestByType(EvidenceType.TIMELINE_OVERTIME_MINUTES)
                        .orElseThrow().value().isUnknown()),
                () -> assertTrue(corrupt.latestByType(EvidenceType.TIMELINE_OVERTIME_MINUTES)
                        .orElseThrow().value().isInvalid()),
                () -> assertEquals(2, imAddr.messages().size()),
                () -> assertEquals(SpeakerRole.CUSTOMER, imAddr.findMessageById("EV-IM-01").orElseThrow().speakerRole()),
                () -> assertEquals(SpeakerRole.RIDER, imAddr.findMessageById("EV-IM-02").orElseThrow().speakerRole()),
                () -> assertTrue(imAddr.containsEvidenceId("EV-IM-01")),
                () -> assertEquals(SpeakerRole.RIDER, imAdv.findMessageById("EV-IM-02").orElseThrow().speakerRole()));
    }

    @Test
    void supportsImmutableHumanAttestationWithNewHashAndLatestLookup() {
        EvidenceSnapshot initial = source.collect("ORD-IM-DAMAGE-01").orElseThrow();
        assertTrue(initial.latestByType(EvidenceType.DAMAGE_LEVEL).orElseThrow().value().isUnknown());

        Instant attestedAt = Instant.parse("2026-09-01T04:10:00Z");
        EvidenceSnapshot attested = initial.withHumanAttestation(
                "EV-HUMAN-01",
                EvidenceType.DAMAGE_LEVEL,
                EvidenceValue.known(DamageLevel.PARTIAL, "human:op-1", attestedAt, initial.asOf()),
                attestedAt);

        assertAll(
                () -> assertNotEquals(initial.contentHash(), attested.contentHash()),
                () -> assertTrue(initial.latestByType(EvidenceType.DAMAGE_LEVEL).orElseThrow().value().isUnknown(),
                        "original snapshot must remain immutable"),
                () -> assertEquals(Optional.of(DamageLevel.PARTIAL),
                        attested.latestByType(EvidenceType.DAMAGE_LEVEL).orElseThrow().value().knownValue()),
                () -> assertTrue(attested.containsEvidenceId("EV-HUMAN-01")));
    }

    @Test
    void propagatesExplicitAsOfOverrideIntoCapacityAndSnapshot() {
        Instant customAsOf = Instant.parse("2026-09-01T03:00:00Z");
        EvidenceSnapshot snap = source.collect("ORD-CAPACITY-01", customAsOf).orElseThrow();
        EvidenceValue.Known<?> capRatio = (EvidenceValue.Known<?>) snap.findById("EV-CAP-RATIO")
                .orElseThrow().value();

        assertEquals(customAsOf, snap.asOf());
        assertEquals(customAsOf, capRatio.asOf());
        assertEquals(0.95, capRatio.value());
    }
}

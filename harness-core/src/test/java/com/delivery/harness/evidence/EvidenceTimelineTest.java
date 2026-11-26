package com.delivery.harness.evidence;

import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.evidence.EvidenceValue;
import com.delivery.harness.common.evidence.MinuteRoundingMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the three-valued timeline contract: missing timestamps propagate as
 * Unknown (never silently zero), reversed timestamps propagate as Invalid
 * (never complete), and rounding/zone semantics are explicit and reproducible.
 */
class EvidenceTimelineTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant FIXED_NOW = Instant.parse("2026-09-01T04:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, SHANGHAI);
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 1, 11, 0);

    @Test
    void splitsACompleteValidOrderIntoItsFourLegsWithProvenance() {
        EvidenceTimeline timeline = EvidenceTimeline.of(
                order(3, 7, 28, 20, 40), FIXED_CLOCK, MinuteRoundingMode.FLOOR_TRUNCATE);

        assertAll(
                () -> assertEquals(Optional.of(3L), timeline.dispatchWait().knownValue()),
                () -> assertEquals(Optional.of(7L), timeline.toShop().knownValue()),
                () -> assertEquals(Optional.of(28L), timeline.merchantPrep().knownValue()),
                () -> assertEquals(Optional.of(20L), timeline.onRoad().knownValue()),
                () -> assertEquals(Optional.of(58L), timeline.actual().knownValue()),
                () -> assertEquals(Optional.of(40L), timeline.promised().knownValue()),
                () -> assertEquals(Optional.of(18L), timeline.overtimeMinutesOrEmpty()),
                () -> assertEquals(Optional.of(true), timeline.overtimeStatus().knownValue()),
                () -> assertEquals(EvidenceTimeline.TimelineIntegrity.COMPLETE_VALID, timeline.integrity()),
                () -> assertTrue(timeline.complete()),
                () -> assertTrue(timeline.anomalies().isEmpty()),
                () -> assertEquals(FIXED_NOW,
                        ((EvidenceValue.Known<Long>) timeline.overtimeEvidence()).observedAt()),
                () -> assertEquals(SHANGHAI, timeline.zone().knownValue().orElseThrow()));
    }

    @Test
    void attributesToTheLongestLegWhenCompleteAndOvertime() {
        assertAll(
                () -> assertEquals(EvidenceTimeline.LEG_MERCHANT_PREP,
                        EvidenceTimeline.of(order(3, 7, 28, 20, 40)).dominantLeg()),
                () -> assertEquals(EvidenceTimeline.LEG_DISPATCH_WAIT,
                        EvidenceTimeline.of(order(22, 8, 6, 20, 40)).dominantLeg()),
                () -> assertEquals(EvidenceTimeline.LEG_ON_ROAD,
                        EvidenceTimeline.of(order(4, 9, 8, 44, 40)).dominantLeg()),
                () -> assertEquals(EvidenceTimeline.LEG_TO_SHOP,
                        EvidenceTimeline.of(order(4, 30, 8, 20, 40)).dominantLeg()));
    }

    @Test
    void reportsNoAnomalyOnlyWhenDeliveredWithinPromiseAndComplete() {
        EvidenceTimeline timeline =
                EvidenceTimeline.of(order(3, 7, 9, 18, 45), FIXED_CLOCK, MinuteRoundingMode.FLOOR_TRUNCATE);

        assertAll(
                () -> assertFalse(timeline.overtime()),
                () -> assertEquals(Optional.of(false), timeline.overtimeStatus().knownValue()),
                () -> assertEquals(Optional.of(0L), timeline.overtimeMinutesOrEmpty()),
                () -> assertEquals(EvidenceTimeline.LEG_NONE, timeline.dominantLeg()),
                () -> assertEquals("无异常", timeline.dominantLegLabel()));
    }

    @Test
    void neverReportsNegativeOvertimeWhenDeliveredEarly() {
        EvidenceTimeline timeline = EvidenceTimeline.of(order(1, 2, 3, 4, 120));

        assertEquals(Optional.of(0L), timeline.overtimeMinutesOrEmpty());
        assertFalse(timeline.overtime());
    }

    @Test
    void propagatesMissingDeliveryTimeAsUnknownInsteadOfNoAnomaly() {
        OrderInfo missingDelivery = OrderInfo.builder()
                .orderId("MISSING-DELIVERY")
                .createTime(CREATED)
                .acceptTime(CREATED.plusMinutes(3))
                .arriveShopTime(CREATED.plusMinutes(10))
                .pickupTime(CREATED.plusMinutes(20))
                .expectedDeliveryTime(CREATED.plusMinutes(40))
                .zoneId("Asia/Shanghai")
                .build();

        EvidenceTimeline timeline =
                EvidenceTimeline.of(missingDelivery, FIXED_CLOCK, MinuteRoundingMode.FLOOR_TRUNCATE);
        Map<String, Object> flat = timeline.asMap();

        assertAll(
                () -> assertTrue(timeline.actual().isUnknown()),
                () -> assertTrue(timeline.onRoad().isUnknown()),
                () -> assertTrue(timeline.overtimeEvidence().isUnknown()),
                () -> assertTrue(timeline.overtimeStatus().isUnknown()),
                () -> assertEquals(EvidenceTimeline.TimelineIntegrity.INCOMPLETE, timeline.integrity()),
                () -> assertFalse(timeline.complete()),
                () -> assertEquals(EvidenceTimeline.LEG_UNKNOWN, timeline.dominantLeg()),
                () -> assertEquals("信息不足", timeline.dominantLegLabel()),
                () -> assertNull(flat.get("actual_minutes")),
                () -> assertNull(flat.get("overtime_minutes")),
                () -> assertEquals(false, flat.get("overtime_known")),
                () -> assertTrue(timeline.anomalies().contains("MISSING:deliveryTime")));
    }

    @Test
    void propagatesMissingPromisedTimeAsUnknownInsteadOfInflatingOvertime() {
        OrderInfo missingPromised = OrderInfo.builder()
                .orderId("MISSING-PROMISED")
                .createTime(CREATED)
                .acceptTime(CREATED.plusMinutes(3))
                .arriveShopTime(CREATED.plusMinutes(10))
                .pickupTime(CREATED.plusMinutes(20))
                .deliveryTime(CREATED.plusMinutes(50))
                .zoneId("Asia/Shanghai")
                .build();

        EvidenceTimeline timeline =
                EvidenceTimeline.of(missingPromised, FIXED_CLOCK, MinuteRoundingMode.FLOOR_TRUNCATE);

        assertAll(
                () -> assertEquals(Optional.of(50L), timeline.actual().knownValue()),
                () -> assertTrue(timeline.promised().isUnknown()),
                () -> assertTrue(timeline.overtimeEvidence().isUnknown()),
                () -> assertFalse(timeline.overtime()),
                () -> assertEquals(EvidenceTimeline.TimelineIntegrity.INCOMPLETE, timeline.integrity()),
                () -> assertEquals(EvidenceTimeline.LEG_UNKNOWN, timeline.dominantLeg()),
                () -> assertTrue(timeline.anomalies().contains("MISSING:expectedDeliveryTime")));
    }

    @Test
    void marksOutOfOrderTimestampsAsInvalidAndCorrupted() {
        OrderInfo reversed = OrderInfo.builder()
                .orderId("REVERSED")
                .createTime(CREATED)
                .acceptTime(CREATED.minusMinutes(5))
                .arriveShopTime(CREATED.plusMinutes(7))
                .pickupTime(CREATED.plusMinutes(15))
                .deliveryTime(CREATED.plusMinutes(35))
                .expectedDeliveryTime(CREATED.plusMinutes(40))
                .zoneId("Asia/Shanghai")
                .build();

        EvidenceTimeline timeline = EvidenceTimeline.of(reversed, FIXED_CLOCK, MinuteRoundingMode.FLOOR_TRUNCATE);

        assertAll(
                () -> assertTrue(timeline.dispatchWait().isInvalid()),
                () -> assertTrue(timeline.overtimeEvidence().isInvalid()),
                () -> assertTrue(timeline.overtimeStatus().isInvalid()),
                () -> assertEquals(EvidenceTimeline.TimelineIntegrity.CORRUPTED_TIMESTAMPS, timeline.integrity()),
                () -> assertFalse(timeline.complete()),
                () -> assertEquals(EvidenceTimeline.LEG_INVALID, timeline.dominantLeg()),
                () -> assertEquals("时间轴异常", timeline.dominantLegLabel()),
                () -> assertTrue(timeline.anomalies().contains("OUT_OF_ORDER:acceptTime<createTime")));
    }

    @Test
    void detectsNonAdjacentTimestampReversalWhenIntermediateLegIsMissing() {
        OrderInfo nonAdjacentReversed = OrderInfo.builder()
                .orderId("NON-ADJACENT")
                .createTime(CREATED)
                .acceptTime(CREATED.plusMinutes(20))
                .arriveShopTime(null)
                .pickupTime(CREATED.plusMinutes(10))
                .deliveryTime(CREATED.plusMinutes(30))
                .expectedDeliveryTime(CREATED.plusMinutes(40))
                .build();

        EvidenceTimeline timeline =
                EvidenceTimeline.of(nonAdjacentReversed, FIXED_CLOCK, MinuteRoundingMode.FLOOR_TRUNCATE);

        assertAll(
                () -> assertEquals(EvidenceTimeline.TimelineIntegrity.CORRUPTED_TIMESTAMPS, timeline.integrity()),
                () -> assertTrue(timeline.overtimeEvidence().isInvalid()),
                () -> assertTrue(timeline.anomalies().contains("OUT_OF_ORDER:pickupTime<acceptTime")));
    }

    @Test
    void marksMalformedZoneIdAsInvalid() {
        OrderInfo badZone = order(3, 7, 10, 15, 40);
        badZone.setZoneId("Mars/Olympus_Mons");

        EvidenceTimeline timeline = EvidenceTimeline.of(badZone, FIXED_CLOCK, MinuteRoundingMode.FLOOR_TRUNCATE);

        assertAll(
                () -> assertTrue(timeline.zone().isInvalid()),
                () -> assertEquals(EvidenceTimeline.TimelineIntegrity.CORRUPTED_TIMESTAMPS, timeline.integrity()),
                () -> assertFalse(timeline.complete()),
                () -> assertTrue(timeline.anomalies().contains("INVALID_ZONE:Mars/Olympus_Mons")));
    }

    @Test
    void respectsExplicitMinuteRoundingModeOnSubMinuteBoundaries() {
        OrderInfo boundary = OrderInfo.builder()
                .orderId("BOUNDARY")
                .createTime(CREATED)
                .acceptTime(CREATED.plusMinutes(2))
                .arriveShopTime(CREATED.plusMinutes(7))
                .pickupTime(CREATED.plusMinutes(15))
                .deliveryTime(CREATED.plusMinutes(55).plusSeconds(59))
                .expectedDeliveryTime(CREATED.plusMinutes(40))
                .zoneId("Asia/Shanghai")
                .build();

        EvidenceTimeline floored = EvidenceTimeline.of(boundary, FIXED_CLOCK, MinuteRoundingMode.FLOOR_TRUNCATE);
        EvidenceTimeline ceiled = EvidenceTimeline.of(boundary, FIXED_CLOCK, MinuteRoundingMode.CEIL);

        assertEquals(Optional.of(15L), floored.overtimeMinutesOrEmpty());
        assertEquals(Optional.of(16L), ceiled.overtimeMinutesOrEmpty());
    }

    @Test
    void toleratesANullOrderWithoutFabricatingZero() {
        EvidenceTimeline timeline = EvidenceTimeline.of(null, FIXED_CLOCK, MinuteRoundingMode.FLOOR_TRUNCATE);

        assertAll(
                () -> assertFalse(timeline.complete()),
                () -> assertTrue(timeline.actual().isUnknown()),
                () -> assertTrue(timeline.overtimeEvidence().isUnknown()),
                () -> assertEquals(EvidenceTimeline.LEG_UNKNOWN, timeline.dominantLeg()),
                () -> assertNull(timeline.asMap().get("actual_minutes")));
    }

    @Test
    void exposesStructuredMapForPromptsAndDiagnostics() {
        Map<String, Object> flat = EvidenceTimeline.of(order(3, 7, 28, 20, 40)).asMap();
        assertAll(
                () -> assertEquals(14, flat.size()),
                () -> assertEquals("COMPLETE_VALID", flat.get("timeline_integrity")),
                () -> assertEquals("Asia/Shanghai", flat.get("zone_id")),
                () -> assertEquals(true, flat.get("overtime_known")));
    }

    private static OrderInfo order(int accept, int toShop, int prep, int onRoad, int promised) {
        LocalDateTime accepted = CREATED.plusMinutes(accept);
        LocalDateTime arrivedShop = accepted.plusMinutes(toShop);
        LocalDateTime pickedUp = arrivedShop.plusMinutes(prep);
        return OrderInfo.builder()
                .orderId("T-1")
                .createTime(CREATED)
                .acceptTime(accepted)
                .arriveShopTime(arrivedShop)
                .pickupTime(pickedUp)
                .deliveryTime(pickedUp.plusMinutes(onRoad))
                .expectedDeliveryTime(CREATED.plusMinutes(promised))
                .zoneId("Asia/Shanghai")
                .build();
    }
}

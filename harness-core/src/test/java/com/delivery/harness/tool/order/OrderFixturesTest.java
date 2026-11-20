package com.delivery.harness.tool.order;

import com.delivery.harness.common.dto.OrderInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the properties the fixture set rests on: pinned orders vary
 * reproducibly, internal scenario labels never leak into order payloads, and
 * unknown order IDs return empty instead of being fabricated.
 */
class OrderFixturesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 12, 0);

    @Test
    void producesDifferentTimelinesForDifferentOrders() {
        OrderInfo merchantSlow = OrderFixtures.find("TEST001", NOW).orElseThrow();
        OrderInfo capacityShort = OrderFixtures.find("TEST002", NOW).orElseThrow();

        assertAll(
                () -> assertEquals("S001", merchantSlow.getStationId()),
                () -> assertEquals("S002", capacityShort.getStationId()),
                // Same total overtime, different cause. This pair is the one
                // that separates a system reading the evidence from one
                // pattern-matching on "how late was it".
                () -> assertEquals(merchantSlow.getExtraInfo().get("overtime_minutes"),
                        capacityShort.getExtraInfo().get("overtime_minutes")),
                () -> assertNotEqualLegs(merchantSlow, capacityShort));
    }

    @Test
    void neverLeaksScenarioKeyIntoExtraInfo() {
        for (String orderId : OrderFixtures.pinnedOrderIds()) {
            OrderInfo order = OrderFixtures.find(orderId, NOW).orElseThrow();
            assertFalse(order.getExtraInfo().containsKey("scenario_key"),
                    "scenario_key must not leak into order payload for " + orderId);
        }
    }

    @Test
    void isDeterministicForTheSamePinnedOrderId() {
        OrderInfo first = OrderFixtures.find("DEMO-001", NOW).orElseThrow();
        OrderInfo second = OrderFixtures.find("DEMO-001", NOW).orElseThrow();

        assertEquals(first.getStationId(), second.getStationId());
        assertEquals(first.getOrderAmount(), second.getOrderAmount());
        assertEquals(first.getCreateTime(), second.getCreateTime());
        assertEquals(first.getDeliveryTime(), second.getDeliveryTime());
    }

    @Test
    void returnsEmptyForUnknownBlankOrNullOrderIds() {
        assertAll(
                () -> assertEquals(Optional.empty(), OrderFixtures.find("ORD-DOES-NOT-EXIST", NOW)),
                () -> assertEquals(Optional.empty(), OrderFixtures.find("ARBITRARY-123", NOW)),
                () -> assertEquals(Optional.empty(), OrderFixtures.find("   ", NOW)),
                () -> assertEquals(Optional.empty(), OrderFixtures.find(null, NOW)));
    }

    @Test
    void includesAnOrderThatWasDeliveredOnTime() {
        OrderInfo onTime = OrderFixtures.find("TEST005", NOW).orElseThrow();

        assertAll(
                () -> assertFalse(onTime.getIsAbnormal()),
                () -> assertEquals("NONE", onTime.getAbnormalType()),
                () -> assertEquals(0, onTime.getExtraInfo().get("overtime_minutes")),
                () -> assertTrue(onTime.getDeliveryTime().isBefore(onTime.getExpectedDeliveryTime()),
                        "an on-time fixture must actually beat its promise"));
    }

    @Test
    void buildsAnInternallyConsistentTimeline() {
        OrderInfo order = OrderFixtures.find("TEST003", NOW).orElseThrow();

        long actual = Duration.between(order.getCreateTime(), order.getDeliveryTime()).toMinutes();
        long legs = Duration.between(order.getCreateTime(), order.getAcceptTime()).toMinutes()
                + Duration.between(order.getAcceptTime(), order.getArriveShopTime()).toMinutes()
                + Duration.between(order.getArriveShopTime(), order.getPickupTime()).toMinutes()
                + Duration.between(order.getPickupTime(), order.getDeliveryTime()).toMinutes();

        assertAll(
                () -> assertEquals(actual, legs, "the legs must add up to the total"),
                () -> assertEquals(actual * 60, order.getActualDurationSeconds().longValue()),
                () -> assertEquals(5, order.getEvents().size()),
                () -> assertTrue(order.getIsAbnormal()),
                () -> assertEquals(35, order.getExtraInfo().get("overtime_minutes"),
                        "TEST003 backs the severe-tier compensation case"));
    }

    @Test
    void everyPinnedOrderIdResolves() {
        for (String orderId : OrderFixtures.pinnedOrderIds()) {
            assertNotNull(OrderFixtures.find(orderId, NOW).orElseThrow().getStationId(), orderId);
        }
    }

    @Test
    void marksItsDataAsSynthetic() {
        assertEquals(true, OrderFixtures.find("TEST001", NOW).orElseThrow().getExtraInfo().get("synthetic"));
    }

    private static void assertNotEqualLegs(OrderInfo a, OrderInfo b) {
        assertFalse(a.getExtraInfo().get("merchant_prep_minutes")
                        .equals(b.getExtraInfo().get("merchant_prep_minutes"))
                && a.getExtraInfo().get("dispatch_wait_minutes")
                        .equals(b.getExtraInfo().get("dispatch_wait_minutes")),
                "the two scenarios must differ in where the time went");
    }
}

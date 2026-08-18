package com.delivery.harness.tool.order;

import com.delivery.harness.common.dto.OrderInfo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the property the whole harness rests on: the synthetic order varies
 * with the order ID, and does so reproducibly.
 */
class OrderFixturesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 12, 0);

    @Test
    void producesDifferentTimelinesForDifferentOrders() {
        OrderInfo merchantSlow = OrderFixtures.build("TEST001", NOW);
        OrderInfo capacityShort = OrderFixtures.build("TEST002", NOW);

        assertAll(
                () -> assertEquals("MERCHANT_SLOW", merchantSlow.getExtraInfo().get("scenario_key")),
                () -> assertEquals("CAPACITY_SHORT", capacityShort.getExtraInfo().get("scenario_key")),
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
    void isDeterministicForTheSameOrderId() {
        OrderInfo first = OrderFixtures.build("ARBITRARY-123", NOW);
        OrderInfo second = OrderFixtures.build("ARBITRARY-123", NOW);

        assertEquals(first.getExtraInfo().get("scenario_key"), second.getExtraInfo().get("scenario_key"));
        assertEquals(first.getStationId(), second.getStationId());
        assertEquals(first.getOrderAmount(), second.getOrderAmount());
    }

    @Test
    void assignsUnknownOrderIdsAcrossTheScenarioSet() {
        Set<Object> scenarios = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            scenarios.add(OrderFixtures.build("UNPINNED-" + i, NOW).getExtraInfo().get("scenario_key"));
        }
        assertTrue(scenarios.size() > 1,
                "unknown order IDs must not all collapse onto one scenario");
    }

    @Test
    void includesAnOrderThatWasDeliveredOnTime() {
        OrderInfo onTime = OrderFixtures.build("TEST005", NOW);

        assertAll(
                () -> assertFalse(onTime.getIsAbnormal()),
                () -> assertEquals("NONE", onTime.getAbnormalType()),
                () -> assertEquals(0, onTime.getExtraInfo().get("overtime_minutes")),
                () -> assertTrue(onTime.getDeliveryTime().isBefore(onTime.getExpectedDeliveryTime()),
                        "an on-time fixture must actually beat its promise"));
    }

    @Test
    void buildsAnInternallyConsistentTimeline() {
        OrderInfo order = OrderFixtures.build("TEST003", NOW);

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
            assertNotNull(OrderFixtures.build(orderId, NOW).getStationId(), orderId);
        }
    }

    @Test
    void marksItsDataAsSynthetic() {
        assertEquals(true, OrderFixtures.build("TEST001", NOW).getExtraInfo().get("synthetic"));
    }

    private static void assertNotEqualLegs(OrderInfo a, OrderInfo b) {
        assertFalse(a.getExtraInfo().get("merchant_prep_minutes")
                        .equals(b.getExtraInfo().get("merchant_prep_minutes"))
                && a.getExtraInfo().get("dispatch_wait_minutes")
                        .equals(b.getExtraInfo().get("dispatch_wait_minutes")),
                "the two scenarios must differ in where the time went");
    }
}

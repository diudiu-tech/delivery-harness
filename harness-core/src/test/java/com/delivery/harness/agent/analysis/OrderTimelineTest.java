package com.delivery.harness.agent.analysis;

import com.delivery.harness.common.dto.OrderInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic baseline the model has to beat. If this is wrong, every
 * attribution downstream is measured against the wrong yardstick.
 */
class OrderTimelineTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 18, 11, 0);

    @Test
    void splitsAnOrderIntoItsFourLegs() {
        OrderTimeline timeline = OrderTimeline.of(order(3, 7, 28, 20, 40));

        assertAll(
                () -> assertEquals(3, timeline.dispatchWaitMinutes()),
                () -> assertEquals(7, timeline.toShopMinutes()),
                () -> assertEquals(28, timeline.merchantPrepMinutes()),
                () -> assertEquals(20, timeline.onRoadMinutes()),
                () -> assertEquals(58, timeline.actualMinutes()),
                () -> assertEquals(40, timeline.promisedMinutes()),
                () -> assertEquals(18, timeline.overtimeMinutes()),
                () -> assertTrue(timeline.complete()));
    }

    @Test
    void attributesToTheLongestLeg() {
        assertAll(
                () -> assertEquals(OrderTimeline.LEG_MERCHANT_PREP,
                        OrderTimeline.of(order(3, 7, 28, 20, 40)).dominantLeg()),
                () -> assertEquals(OrderTimeline.LEG_DISPATCH_WAIT,
                        OrderTimeline.of(order(22, 8, 6, 20, 40)).dominantLeg()),
                () -> assertEquals(OrderTimeline.LEG_ON_ROAD,
                        OrderTimeline.of(order(4, 9, 8, 44, 40)).dominantLeg()),
                () -> assertEquals(OrderTimeline.LEG_TO_SHOP,
                        OrderTimeline.of(order(4, 30, 8, 20, 40)).dominantLeg()));
    }

    @Test
    void reportsNoAnomalyWhenDeliveredWithinThePromise() {
        OrderTimeline timeline = OrderTimeline.of(order(3, 7, 9, 18, 45));

        assertAll(
                () -> assertFalse(timeline.overtime()),
                () -> assertEquals(0, timeline.overtimeMinutes()),
                // Not "the longest leg" - an on-time order has no dominant
                // cause, and offering one would invite a spurious attribution.
                () -> assertEquals(OrderTimeline.LEG_NONE, timeline.dominantLeg()),
                () -> assertEquals("无异常", timeline.dominantLegLabel()));
    }

    @Test
    void neverReportsNegativeOvertime() {
        OrderTimeline timeline = OrderTimeline.of(order(1, 2, 3, 4, 120));

        assertEquals(0, timeline.overtimeMinutes());
        assertFalse(timeline.overtime());
    }

    @Test
    void degradesToZeroOnMissingTimestampsAndSaysSo() {
        OrderInfo partial = OrderInfo.builder()
                .orderId("PARTIAL")
                .createTime(CREATED)
                .deliveryTime(CREATED.plusMinutes(50))
                .build();

        OrderTimeline timeline = OrderTimeline.of(partial);

        assertAll(
                () -> assertEquals(50, timeline.actualMinutes()),
                () -> assertEquals(0, timeline.merchantPrepMinutes()),
                () -> assertFalse(timeline.complete(),
                        "an incomplete timeline must be visible, not silently zero"));
    }

    @Test
    void toleratesANullOrder() {
        OrderTimeline timeline = OrderTimeline.of(null);

        assertFalse(timeline.complete());
        assertEquals(0, timeline.actualMinutes());
    }

    @Test
    void exposesEveryLegForPromptsAndScoring() {
        assertEquals(10, OrderTimeline.of(order(3, 7, 28, 20, 40)).asMap().size());
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
                .build();
    }
}

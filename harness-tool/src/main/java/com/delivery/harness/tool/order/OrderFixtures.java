package com.delivery.harness.tool.order;

import com.delivery.harness.common.dto.OrderInfo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic synthetic orders for the mock {@code order_query} tool.
 *
 * <p>Every field is fabricated. Nothing here corresponds to a real order,
 * merchant, courier or address.
 *
 * <p>The important property is that different order IDs produce
 * <em>different</em> timelines. An earlier revision returned one hard-coded
 * order for every input, which made the whole pipeline insensitive to its own
 * request: two different orders received identical evidence, so no evaluation
 * could tell a model that read the evidence from one that ignored it.
 *
 * <p>Known IDs map to a named scenario. Unknown IDs are assigned a scenario by
 * a stable hash of the ID, so an arbitrary request is still reproducible: the
 * same ID always yields the same timeline.
 */
public final class OrderFixtures {

    private OrderFixtures() {}

    /**
     * The shape of one synthetic order, expressed as durations in minutes
     * relative to order creation. Timestamps are derived from these so that a
     * fixture stays internally consistent whenever it is generated.
     */
    record Scenario(
            String key,
            String rootCauseHint,
            int acceptDelayMinutes,
            int toShopMinutes,
            int waitAtShopMinutes,
            int onRoadMinutes,
            int promisedMinutes,
            String stationId,
            String stationName,
            String merchantName,
            double pickupLat,
            double pickupLng,
            double deliveryLat,
            double deliveryLng,
            String orderAmount,
            String weather) {

        int actualMinutes() {
            return acceptDelayMinutes + toShopMinutes + waitAtShopMinutes + onRoadMinutes;
        }

        int overtimeMinutes() {
            return Math.max(0, actualMinutes() - promisedMinutes);
        }

        boolean abnormal() {
            return overtimeMinutes() > 0;
        }
    }

    /** Courier waited far longer at the shop than the other legs took. */
    static final Scenario MERCHANT_SLOW = new Scenario(
            "MERCHANT_SLOW", "商家出餐慢",
            3, 7, 28, 20, 40,
            "S001", "示例一站", "示例餐厅A",
            39.9087, 116.3975, 39.9150, 116.4050,
            "35.50", "晴");

    /** Same total overtime as MERCHANT_SLOW, but the delay is before acceptance. */
    static final Scenario CAPACITY_SHORT = new Scenario(
            "CAPACITY_SHORT", "运力不足",
            22, 8, 6, 22, 40,
            "S002", "示例二站", "示例餐厅B",
            39.9200, 116.4100, 39.9260, 116.4180,
            "42.00", "晴");

    /** Road leg inflated; weather is the differentiating signal. */
    static final Scenario WEATHER_DELAY = new Scenario(
            "WEATHER_DELAY", "天气异常",
            4, 9, 8, 44, 40,
            "S003", "示例三站", "示例餐厅C",
            39.9310, 116.4220, 39.9405, 116.4390,
            "28.80", "暴雨");

    /** Road leg inflated without a weather explanation. */
    static final Scenario RIDER_DETOUR = new Scenario(
            "RIDER_DETOUR", "骑手绕路",
            3, 6, 7, 38, 40,
            "S001", "示例一站", "示例餐厅D",
            39.9087, 116.3975, 39.9120, 116.4010,
            "56.20", "多云");

    /** Overtime above 30 minutes, which is the top compensation tier. */
    static final Scenario SEVERE_MERCHANT_SLOW = new Scenario(
            "SEVERE_MERCHANT_SLOW", "商家出餐慢",
            4, 6, 45, 20, 40,
            "S002", "示例二站", "示例餐厅E",
            39.9200, 116.4100, 39.9245, 116.4150,
            "64.00", "阴");

    /**
     * Delivered inside the promise. Present so the system can be asked a
     * question whose correct answer is "nothing went wrong" — without it,
     * always answering "overtime" scores perfectly.
     */
    static final Scenario ON_TIME = new Scenario(
            "ON_TIME", "无异常",
            3, 7, 9, 18, 45,
            "S001", "示例一站", "示例餐厅F",
            39.9087, 116.3975, 39.9130, 116.4020,
            "31.00", "晴");

    private static final List<Scenario> ALL = List.of(
            MERCHANT_SLOW, CAPACITY_SHORT, WEATHER_DELAY, RIDER_DETOUR, SEVERE_MERCHANT_SLOW, ON_TIME);

    private static final Map<String, Scenario> BY_ORDER_ID = new LinkedHashMap<>();

    static {
        // Documented demo orders used by the README examples.
        BY_ORDER_ID.put("DEMO-001", MERCHANT_SLOW);
        BY_ORDER_ID.put("DEMO-002", SEVERE_MERCHANT_SLOW);
        BY_ORDER_ID.put("DEMO-003", WEATHER_DELAY);
        BY_ORDER_ID.put("DEMO-004", ON_TIME);
        BY_ORDER_ID.put("DEMO-005", CAPACITY_SHORT);
        BY_ORDER_ID.put("DEMO-006", RIDER_DETOUR);

        // Orders referenced by the seeded evaluation cases. Changing these
        // changes what those cases measure; keep them in step with
        // harness-eval/src/main/resources/seed/eval-cases.json.
        BY_ORDER_ID.put("TEST001", MERCHANT_SLOW);
        BY_ORDER_ID.put("TEST002", CAPACITY_SHORT);
        BY_ORDER_ID.put("TEST003", SEVERE_MERCHANT_SLOW);
        BY_ORDER_ID.put("TEST004", MERCHANT_SLOW);
        BY_ORDER_ID.put("TEST005", ON_TIME);
    }

    /** Scenario for an order ID. Never null; unknown IDs hash to a stable scenario. */
    static Scenario scenarioFor(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return MERCHANT_SLOW;
        }
        Scenario known = BY_ORDER_ID.get(orderId);
        if (known != null) {
            return known;
        }
        int index = Math.floorMod(orderId.hashCode(), ALL.size());
        return ALL.get(index);
    }

    /** Order IDs with an explicitly pinned scenario. */
    public static List<String> pinnedOrderIds() {
        return List.copyOf(BY_ORDER_ID.keySet());
    }

    /**
     * Builds the synthetic order. {@code now} is the delivery moment, so the
     * timeline runs backwards from it and a freshly generated fixture always
     * looks like an order that has just completed.
     */
    static OrderInfo build(String orderId, LocalDateTime now) {
        Scenario s = scenarioFor(orderId);

        LocalDateTime delivered = now;
        LocalDateTime created = delivered.minusMinutes(s.actualMinutes());
        LocalDateTime accepted = created.plusMinutes(s.acceptDelayMinutes());
        LocalDateTime arrivedShop = accepted.plusMinutes(s.toShopMinutes());
        LocalDateTime pickedUp = arrivedShop.plusMinutes(s.waitAtShopMinutes());
        LocalDateTime promised = created.plusMinutes(s.promisedMinutes());

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("scenario_key", s.key());
        extra.put("weather", s.weather());
        extra.put("promised_minutes", s.promisedMinutes());
        extra.put("actual_minutes", s.actualMinutes());
        extra.put("overtime_minutes", s.overtimeMinutes());
        extra.put("merchant_prep_minutes", s.waitAtShopMinutes());
        extra.put("dispatch_wait_minutes", s.acceptDelayMinutes());
        extra.put("on_road_minutes", s.onRoadMinutes());
        extra.put("synthetic", true);

        return OrderInfo.builder()
                .orderId(orderId)
                .merchantId("M-" + s.stationId())
                .merchantName(s.merchantName())
                .riderId("R-" + s.stationId())
                .riderName("示例骑手")
                .userId("U-SYNTHETIC")
                .status("DELIVERED")
                .pickupAddress("示例商圈" + s.stationName() + "取餐点")
                .deliveryAddress("示例小区" + s.stationName() + "送达点")
                .pickupLat(s.pickupLat())
                .pickupLng(s.pickupLng())
                .deliveryLat(s.deliveryLat())
                .deliveryLng(s.deliveryLng())
                .createTime(created)
                .acceptTime(accepted)
                .arriveShopTime(arrivedShop)
                .pickupTime(pickedUp)
                .deliveryTime(delivered)
                .expectedDeliveryTime(promised)
                .estimatedDurationSeconds(s.promisedMinutes() * 60)
                .actualDurationSeconds(s.actualMinutes() * 60)
                .orderAmount(new BigDecimal(s.orderAmount()))
                .deliveryFee(new BigDecimal("5.00"))
                .stationId(s.stationId())
                .stationName(s.stationName())
                .cityCode("010")
                .isAbnormal(s.abnormal())
                .abnormalType(s.abnormal() ? "OVERTIME" : "NONE")
                .events(buildEvents(created, accepted, arrivedShop, pickedUp, delivered))
                .extraInfo(extra)
                .build();
    }

    private static List<OrderInfo.OrderEvent> buildEvents(
            LocalDateTime created,
            LocalDateTime accepted,
            LocalDateTime arrivedShop,
            LocalDateTime pickedUp,
            LocalDateTime delivered) {
        List<OrderInfo.OrderEvent> events = new ArrayList<>();
        for (Object[] row : Arrays.asList(
                new Object[] {"ORDER_CREATED", "订单创建", created},
                new Object[] {"RIDER_ACCEPTED", "骑手接单", accepted},
                new Object[] {"RIDER_ARRIVED_SHOP", "骑手到店", arrivedShop},
                new Object[] {"ORDER_PICKED_UP", "骑手取餐", pickedUp},
                new Object[] {"ORDER_DELIVERED", "订单送达", delivered})) {
            events.add(OrderInfo.OrderEvent.builder()
                    .eventType((String) row[0])
                    .description((String) row[1])
                    .eventTime((LocalDateTime) row[2])
                    .build());
        }
        return events;
    }
}

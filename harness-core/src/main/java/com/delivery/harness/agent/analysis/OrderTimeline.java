package com.delivery.harness.agent.analysis;

import com.delivery.harness.common.dto.OrderInfo;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derived timings for one order.
 *
 * <p>Splits an order into the four legs that a delivery operator actually
 * attributes delay to, so downstream steps have structured evidence instead of
 * a blob of timestamps:
 *
 * <ol>
 *   <li>dispatch wait — created to accepted (a capacity signal)</li>
 *   <li>to shop — accepted to arrived at shop (a distance/traffic signal)</li>
 *   <li>merchant prep — arrived at shop to picked up (a merchant signal)</li>
 *   <li>on road — picked up to delivered (a traffic/weather/route signal)</li>
 * </ol>
 *
 * <p>This is a deterministic computation over the order's own timestamps. It
 * is the baseline the model has to beat: if a model cannot outperform
 * "blame the longest leg", it is adding nothing.
 *
 * <p>Every field degrades to {@code 0} when the corresponding timestamps are
 * absent, and {@link #complete()} reports whether the full timeline was
 * available.
 */
public final class OrderTimeline {

    public static final String LEG_DISPATCH_WAIT = "DISPATCH_WAIT";
    public static final String LEG_TO_SHOP = "TO_SHOP";
    public static final String LEG_MERCHANT_PREP = "MERCHANT_PREP";
    public static final String LEG_ON_ROAD = "ON_ROAD";
    public static final String LEG_NONE = "NONE";

    private final long dispatchWaitMinutes;
    private final long toShopMinutes;
    private final long merchantPrepMinutes;
    private final long onRoadMinutes;
    private final long promisedMinutes;
    private final long actualMinutes;
    private final boolean complete;

    private OrderTimeline(
            long dispatchWaitMinutes,
            long toShopMinutes,
            long merchantPrepMinutes,
            long onRoadMinutes,
            long promisedMinutes,
            long actualMinutes,
            boolean complete) {
        this.dispatchWaitMinutes = dispatchWaitMinutes;
        this.toShopMinutes = toShopMinutes;
        this.merchantPrepMinutes = merchantPrepMinutes;
        this.onRoadMinutes = onRoadMinutes;
        this.promisedMinutes = promisedMinutes;
        this.actualMinutes = actualMinutes;
        this.complete = complete;
    }

    public static OrderTimeline of(OrderInfo order) {
        if (order == null) {
            return new OrderTimeline(0, 0, 0, 0, 0, 0, false);
        }
        boolean complete = order.getCreateTime() != null
                && order.getAcceptTime() != null
                && order.getArriveShopTime() != null
                && order.getPickupTime() != null
                && order.getDeliveryTime() != null
                && order.getExpectedDeliveryTime() != null;

        return new OrderTimeline(
                minutesBetween(order.getCreateTime(), order.getAcceptTime()),
                minutesBetween(order.getAcceptTime(), order.getArriveShopTime()),
                minutesBetween(order.getArriveShopTime(), order.getPickupTime()),
                minutesBetween(order.getPickupTime(), order.getDeliveryTime()),
                minutesBetween(order.getCreateTime(), order.getExpectedDeliveryTime()),
                minutesBetween(order.getCreateTime(), order.getDeliveryTime()),
                complete);
    }

    private static long minutesBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(from, to).toMinutes());
    }

    public long dispatchWaitMinutes() {
        return dispatchWaitMinutes;
    }

    public long toShopMinutes() {
        return toShopMinutes;
    }

    public long merchantPrepMinutes() {
        return merchantPrepMinutes;
    }

    public long onRoadMinutes() {
        return onRoadMinutes;
    }

    public long promisedMinutes() {
        return promisedMinutes;
    }

    public long actualMinutes() {
        return actualMinutes;
    }

    public boolean complete() {
        return complete;
    }

    /** Minutes past the promised delivery time; never negative. */
    public long overtimeMinutes() {
        return Math.max(0L, actualMinutes - promisedMinutes);
    }

    public boolean overtime() {
        return overtimeMinutes() > 0;
    }

    /**
     * The leg that consumed the largest share of elapsed time.
     *
     * <p>Deliberately simple. It is a baseline for the model to beat and an
     * anchor for the evidence chain, not an attribution engine: a long road
     * leg in heavy rain and a long road leg on a clear day look identical
     * here, which is exactly the ambiguity the model is asked to resolve.
     */
    public String dominantLeg() {
        if (!overtime()) {
            return LEG_NONE;
        }
        long max = Math.max(Math.max(dispatchWaitMinutes, toShopMinutes),
                Math.max(merchantPrepMinutes, onRoadMinutes));
        if (max == 0L) {
            return LEG_NONE;
        }
        if (max == merchantPrepMinutes) {
            return LEG_MERCHANT_PREP;
        }
        if (max == dispatchWaitMinutes) {
            return LEG_DISPATCH_WAIT;
        }
        if (max == onRoadMinutes) {
            return LEG_ON_ROAD;
        }
        return LEG_TO_SHOP;
    }

    /** Chinese label for {@link #dominantLeg()}, used to seed retrieval queries. */
    public String dominantLegLabel() {
        return switch (dominantLeg()) {
            case LEG_MERCHANT_PREP -> "商家出餐慢";
            case LEG_DISPATCH_WAIT -> "运力不足";
            case LEG_ON_ROAD -> "路途耗时长";
            case LEG_TO_SHOP -> "骑手到店慢";
            default -> "无异常";
        };
    }

    /** Flat view for prompts, step outputs and evaluation. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dispatch_wait_minutes", dispatchWaitMinutes);
        map.put("to_shop_minutes", toShopMinutes);
        map.put("merchant_prep_minutes", merchantPrepMinutes);
        map.put("on_road_minutes", onRoadMinutes);
        map.put("promised_minutes", promisedMinutes);
        map.put("actual_minutes", actualMinutes);
        map.put("overtime_minutes", overtimeMinutes());
        map.put("dominant_leg", dominantLeg());
        map.put("dominant_leg_label", dominantLegLabel());
        map.put("timeline_complete", complete);
        return map;
    }
}

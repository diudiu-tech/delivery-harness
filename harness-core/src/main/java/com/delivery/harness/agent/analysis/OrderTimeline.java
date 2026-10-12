package com.delivery.harness.agent.analysis;

import com.delivery.harness.common.dto.OrderInfo;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic timings. The v1 view retains numeric compatibility; the v2 view preserves unknowns. */
public final class OrderTimeline {
    public static final String LEG_DISPATCH_WAIT = "DISPATCH_WAIT";
    public static final String LEG_TO_SHOP = "TO_SHOP";
    public static final String LEG_MERCHANT_PREP = "MERCHANT_PREP";
    public static final String LEG_ON_ROAD = "ON_ROAD";
    public static final String LEG_NONE = "NONE";
    private final Map<String, Long> seconds = new LinkedHashMap<>();
    private final Map<String, Object> rawTimes = new LinkedHashMap<>();
    private final List<String> qualityReasons = new ArrayList<>();
    private boolean invalid;
    private String overtimeStatus;
    private Long overtimeSeconds;

    private OrderTimeline() {}

    public static OrderTimeline of(OrderInfo order) {
        OrderTimeline timeline = new OrderTimeline();
        OrderInfo source = order == null ? new OrderInfo() : order;
        timeline.rawTimes.put("create_time", source.getCreateTime());
        timeline.rawTimes.put("accept_time", source.getAcceptTime());
        timeline.rawTimes.put("arrive_shop_time", source.getArriveShopTime());
        timeline.rawTimes.put("pickup_time", source.getPickupTime());
        timeline.rawTimes.put("delivery_time", source.getDeliveryTime());
        timeline.rawTimes.put("expected_delivery_time", source.getExpectedDeliveryTime());
        timeline.add("dispatch_wait", source.getCreateTime(), source.getAcceptTime());
        timeline.add("to_shop", source.getAcceptTime(), source.getArriveShopTime());
        timeline.add("merchant_prep", source.getArriveShopTime(), source.getPickupTime());
        timeline.add("on_road", source.getPickupTime(), source.getDeliveryTime());
        timeline.add("promised", source.getCreateTime(), source.getExpectedDeliveryTime());
        timeline.add("actual", source.getCreateTime(), source.getDeliveryTime());
        boolean unsupported = source.getStatus() != null && !List.of("DELIVERED", "COMPLETED", "FINISHED")
                .contains(source.getStatus().toUpperCase(java.util.Locale.ROOT));
        if (unsupported) {
            timeline.overtimeStatus = "NOT_APPLICABLE";
            timeline.qualityReasons.add("order_not_completed");
        } else if (source.getExpectedDeliveryTime() == null || source.getDeliveryTime() == null
                || (source.getCreateTime() != null && (source.getDeliveryTime().isBefore(source.getCreateTime())
                || source.getExpectedDeliveryTime().isBefore(source.getCreateTime())))) {
            timeline.overtimeStatus = "UNKNOWN";
        } else {
            long late = Duration.between(source.getExpectedDeliveryTime(), source.getDeliveryTime()).getSeconds();
            timeline.overtimeSeconds = Math.max(0, late);
            timeline.overtimeStatus = late > 0 ? "LATE" : "ON_TIME";
        }
        return timeline;
    }

    private void add(String leg, LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            seconds.put(leg, null);
            qualityReasons.add(leg + ":missing_endpoint");
        } else if (to.isBefore(from)) {
            seconds.put(leg, null);
            invalid = true;
            qualityReasons.add(leg + ":reversed_timestamps");
        } else {
            seconds.put(leg, Duration.between(from, to).getSeconds());
        }
    }

    private long minutes(String leg) { return seconds.get(leg) == null ? 0 : seconds.get(leg) / 60; }
    public long dispatchWaitMinutes() { return minutes("dispatch_wait"); }
    public long toShopMinutes() { return minutes("to_shop"); }
    public long merchantPrepMinutes() { return minutes("merchant_prep"); }
    public long onRoadMinutes() { return minutes("on_road"); }
    public long promisedMinutes() { return minutes("promised"); }
    public long actualMinutes() { return minutes("actual"); }
    public boolean complete() { return !invalid && seconds.values().stream().allMatch(java.util.Objects::nonNull); }
    public String qualityStatus() { return invalid ? "INVALID" : complete() ? "VALID" : "PARTIAL"; }
    public String overtimeStatus() { return overtimeStatus; }
    public Long overtimeSeconds() { return overtimeSeconds; }
    public long overtimeMinutes() { return overtimeSeconds == null ? 0 : overtimeSeconds / 60; }
    public boolean overtime() { return "LATE".equals(overtimeStatus); }

    /** Historical single-leg baseline, retained for comparison. */
    public String dominantLeg() {
        if (!overtime()) { return LEG_NONE; }
        long max = Math.max(Math.max(dispatchWaitMinutes(), toShopMinutes()),
                Math.max(merchantPrepMinutes(), onRoadMinutes()));
        if (max == 0) { return LEG_NONE; }
        if (max == merchantPrepMinutes()) { return LEG_MERCHANT_PREP; }
        if (max == dispatchWaitMinutes()) { return LEG_DISPATCH_WAIT; }
        if (max == onRoadMinutes()) { return LEG_ON_ROAD; }
        return LEG_TO_SHOP;
    }

    public String dominantLegLabel() {
        return switch (dominantLeg()) {
            case LEG_MERCHANT_PREP -> "商家出餐慢";
            case LEG_DISPATCH_WAIT -> "运力不足";
            case LEG_ON_ROAD -> "路途耗时长";
            case LEG_TO_SHOP -> "骑手到店慢";
            default -> "无异常";
        };
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        seconds.forEach((leg, value) -> map.put(leg + "_minutes", minutes(leg)));
        map.put("overtime_minutes", overtimeMinutes());
        map.put("dominant_leg", dominantLeg());
        map.put("dominant_leg_label", dominantLegLabel());
        map.put("timeline_complete", complete());
        return map;
    }

    public Map<String, Object> asTrustedMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema_version", "2.0");
        seconds.forEach((leg, value) -> {
            map.put(leg + "_seconds", value);
            map.put(leg + "_minutes", value == null ? null : value / 60.0);
        });
        map.put("overtime_seconds", overtimeSeconds);
        map.put("overtime_minutes", overtimeSeconds == null ? null : overtimeSeconds / 60.0);
        map.put("overtime_status", overtimeStatus);
        map.put("quality_status", qualityStatus());
        map.put("quality_reasons", List.copyOf(qualityReasons));
        map.put("raw_timestamps", new LinkedHashMap<>(rawTimes));
        map.put("time_reference", "LEGACY_LOCAL_TIME");
        map.put("baseline_version", "longest_leg_seconds_v2");
        List<String> tied = new ArrayList<>();
        if (complete() && overtime()) {
            long max = List.of("dispatch_wait", "to_shop", "merchant_prep", "on_road").stream()
                    .mapToLong(seconds::get).max().orElse(0);
            for (String leg : List.of("dispatch_wait", "to_shop", "merchant_prep", "on_road")) {
                if (seconds.get(leg) == max) { tied.add(leg.toUpperCase(java.util.Locale.ROOT)); }
            }
        }
        map.put("dominant_legs", tied);
        map.put("responsibility_determined", false);
        return map;
    }
}

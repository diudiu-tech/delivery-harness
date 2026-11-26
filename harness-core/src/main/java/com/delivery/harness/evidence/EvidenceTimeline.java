package com.delivery.harness.evidence;

import com.delivery.harness.agent.config.HarnessTimeConfig;
import com.delivery.harness.common.dto.OrderInfo;
import com.delivery.harness.common.evidence.EvidenceValue;
import com.delivery.harness.common.evidence.MinuteRoundingMode;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Three-valued deterministic timeline derived from an order's timestamps.
 *
 * <p>Unlike earlier revisions that coerced missing or reversed timestamps to
 * {@code 0}, every duration here is an {@link EvidenceValue}:
 * <ul>
 *   <li>absent timestamps propagate as {@link EvidenceValue.Unknown}</li>
 *   <li>out-of-order timestamps or malformed zones propagate as
 *       {@link EvidenceValue.Invalid}</li>
 *   <li>only monotonic, complete timestamps yield {@link TimelineIntegrity#COMPLETE_VALID}</li>
 * </ul>
 */
public final class EvidenceTimeline {

    public static final String LEG_DISPATCH_WAIT = "DISPATCH_WAIT";
    public static final String LEG_TO_SHOP = "TO_SHOP";
    public static final String LEG_MERCHANT_PREP = "MERCHANT_PREP";
    public static final String LEG_ON_ROAD = "ON_ROAD";
    public static final String LEG_NONE = "NONE";
    public static final String LEG_UNKNOWN = "UNKNOWN";
    public static final String LEG_INVALID = "INVALID";

    private static final String SOURCE = "order_timeline";

    public enum TimelineIntegrity {
        COMPLETE_VALID,
        INCOMPLETE,
        CORRUPTED_TIMESTAMPS
    }

    private final EvidenceValue<Long> dispatchWait;
    private final EvidenceValue<Long> toShop;
    private final EvidenceValue<Long> merchantPrep;
    private final EvidenceValue<Long> onRoad;
    private final EvidenceValue<Long> promised;
    private final EvidenceValue<Long> actual;
    private final EvidenceValue<Long> overtime;
    private final EvidenceValue<ZoneId> zone;
    private final TimelineIntegrity integrity;
    private final List<String> anomalies;
    private final MinuteRoundingMode roundingMode;

    private EvidenceTimeline(
            EvidenceValue<Long> dispatchWait,
            EvidenceValue<Long> toShop,
            EvidenceValue<Long> merchantPrep,
            EvidenceValue<Long> onRoad,
            EvidenceValue<Long> promised,
            EvidenceValue<Long> actual,
            EvidenceValue<Long> overtime,
            EvidenceValue<ZoneId> zone,
            TimelineIntegrity integrity,
            List<String> anomalies,
            MinuteRoundingMode roundingMode) {
        this.dispatchWait = dispatchWait;
        this.toShop = toShop;
        this.merchantPrep = merchantPrep;
        this.onRoad = onRoad;
        this.promised = promised;
        this.actual = actual;
        this.overtime = overtime;
        this.zone = zone;
        this.integrity = integrity;
        this.anomalies = Collections.unmodifiableList(new ArrayList<>(anomalies));
        this.roundingMode = roundingMode;
    }

    public static EvidenceTimeline of(OrderInfo order) {
        return of(order, Clock.system(HarnessTimeConfig.DEFAULT_ZONE), MinuteRoundingMode.FLOOR_TRUNCATE);
    }

    public static EvidenceTimeline of(OrderInfo order, Clock clock, MinuteRoundingMode roundingMode) {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(roundingMode, "roundingMode must not be null");
        Instant observedAt = clock.instant();

        if (order == null) {
            EvidenceValue<Long> missing = EvidenceValue.unknown("MISSING_ORDER", "order is null");
            return new EvidenceTimeline(
                    missing, missing, missing, missing, missing, missing, missing,
                    EvidenceValue.known(clock.getZone(), "clock_default", observedAt),
                    TimelineIntegrity.INCOMPLETE,
                    List.of("MISSING:order"),
                    roundingMode);
        }

        List<String> anomalies = new ArrayList<>();
        EvidenceValue<ZoneId> zoneValue = resolveZone(order.getZoneId(), clock, observedAt, anomalies);
        ZoneId effectiveZone = zoneValue.knownValue().orElse(clock.getZone());

        recordMissing(order.getCreateTime(), "createTime", anomalies);
        recordMissing(order.getAcceptTime(), "acceptTime", anomalies);
        recordMissing(order.getArriveShopTime(), "arriveShopTime", anomalies);
        recordMissing(order.getPickupTime(), "pickupTime", anomalies);
        recordMissing(order.getDeliveryTime(), "deliveryTime", anomalies);
        recordMissing(order.getExpectedDeliveryTime(), "expectedDeliveryTime", anomalies);

        Instant asOf = eventAsOf(order, effectiveZone, observedAt);

        EvidenceValue<Long> dispatchWait = durationBetween(
                "createTime", order.getCreateTime(), "acceptTime", order.getAcceptTime(),
                observedAt, asOf, roundingMode, anomalies);
        EvidenceValue<Long> toShop = durationBetween(
                "acceptTime", order.getAcceptTime(), "arriveShopTime", order.getArriveShopTime(),
                observedAt, asOf, roundingMode, anomalies);
        EvidenceValue<Long> merchantPrep = durationBetween(
                "arriveShopTime", order.getArriveShopTime(), "pickupTime", order.getPickupTime(),
                observedAt, asOf, roundingMode, anomalies);
        EvidenceValue<Long> onRoad = durationBetween(
                "pickupTime", order.getPickupTime(), "deliveryTime", order.getDeliveryTime(),
                observedAt, asOf, roundingMode, anomalies);
        EvidenceValue<Long> promised = durationBetween(
                "createTime", order.getCreateTime(), "expectedDeliveryTime", order.getExpectedDeliveryTime(),
                observedAt, asOf, roundingMode, anomalies);
        EvidenceValue<Long> actual = durationBetween(
                "createTime", order.getCreateTime(), "deliveryTime", order.getDeliveryTime(),
                observedAt, asOf, roundingMode, anomalies);

        checkMonotonicLifecycle(order, anomalies);

        boolean corrupted = zoneValue.isInvalid()
                || dispatchWait.isInvalid()
                || toShop.isInvalid()
                || merchantPrep.isInvalid()
                || onRoad.isInvalid()
                || promised.isInvalid()
                || actual.isInvalid()
                || anomalies.stream().anyMatch(a -> a.startsWith("OUT_OF_ORDER:"));

        boolean allSixPresent = order.getCreateTime() != null
                && order.getAcceptTime() != null
                && order.getArriveShopTime() != null
                && order.getPickupTime() != null
                && order.getDeliveryTime() != null
                && order.getExpectedDeliveryTime() != null;

        TimelineIntegrity integrity;
        if (corrupted) {
            integrity = TimelineIntegrity.CORRUPTED_TIMESTAMPS;
        } else if (allSixPresent) {
            integrity = TimelineIntegrity.COMPLETE_VALID;
        } else {
            integrity = TimelineIntegrity.INCOMPLETE;
        }

        EvidenceValue<Long> overtime = computeOvertime(actual, promised, integrity, observedAt, asOf);

        return new EvidenceTimeline(
                dispatchWait, toShop, merchantPrep, onRoad, promised, actual, overtime,
                zoneValue, integrity, anomalies, roundingMode);
    }

    private static EvidenceValue<ZoneId> resolveZone(
            String rawZoneId, Clock clock, Instant observedAt, List<String> anomalies) {
        if (rawZoneId == null || rawZoneId.isBlank()) {
            return EvidenceValue.known(clock.getZone(), "clock_default", observedAt);
        }
        try {
            return EvidenceValue.known(ZoneId.of(rawZoneId.trim()), "order_info", observedAt);
        } catch (DateTimeException e) {
            anomalies.add("INVALID_ZONE:" + rawZoneId);
            return EvidenceValue.invalid(rawZoneId, "invalid zoneId");
        }
    }

    private static Instant eventAsOf(OrderInfo order, ZoneId zone, Instant fallback) {
        LocalDateTime reference = order.getDeliveryTime() != null
                ? order.getDeliveryTime()
                : order.getCreateTime();
        return reference == null ? fallback : reference.atZone(zone).toInstant();
    }

    private static void recordMissing(LocalDateTime timestamp, String fieldName, List<String> anomalies) {
        if (timestamp == null) {
            anomalies.add("MISSING:" + fieldName);
        }
    }

    private static EvidenceValue<Long> durationBetween(
            String fromName,
            LocalDateTime from,
            String toName,
            LocalDateTime to,
            Instant observedAt,
            Instant asOf,
            MinuteRoundingMode roundingMode,
            List<String> anomalies) {
        if (from == null && to == null) {
            return EvidenceValue.unknown("MISSING_TIMESTAMP", fromName + " and " + toName + " are missing");
        }
        if (from == null) {
            return EvidenceValue.unknown("MISSING_TIMESTAMP", fromName + " is missing");
        }
        if (to == null) {
            return EvidenceValue.unknown("MISSING_TIMESTAMP", toName + " is missing");
        }
        Duration duration = Duration.between(from, to);
        if (duration.isNegative()) {
            String violation = "OUT_OF_ORDER:" + toName + "<" + fromName;
            if (!anomalies.contains(violation)) {
                anomalies.add(violation);
            }
            return EvidenceValue.invalid(from + ".." + to, violation);
        }
        long minutes = roundingMode.toMinutes(duration);
        return EvidenceValue.known(minutes, SOURCE, observedAt, asOf);
    }

    /**
     * Checks chronological monotonicity across all present lifecycle timestamps,
     * catching non-adjacent reversals when an intermediate timestamp is missing
     * (for example acceptTime > pickupTime while arriveShopTime is null).
     */
    private static void checkMonotonicLifecycle(OrderInfo order, List<String> anomalies) {
        String[] names = {"createTime", "acceptTime", "arriveShopTime", "pickupTime", "deliveryTime"};
        LocalDateTime[] values = {
            order.getCreateTime(),
            order.getAcceptTime(),
            order.getArriveShopTime(),
            order.getPickupTime(),
            order.getDeliveryTime()
        };
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                continue;
            }
            for (int j = i + 1; j < values.length; j++) {
                if (values[j] != null && values[j].isBefore(values[i])) {
                    String violation = "OUT_OF_ORDER:" + names[j] + "<" + names[i];
                    if (!anomalies.contains(violation)) {
                        anomalies.add(violation);
                    }
                }
            }
        }
    }

    private static EvidenceValue<Long> computeOvertime(
            EvidenceValue<Long> actual,
            EvidenceValue<Long> promised,
            TimelineIntegrity integrity,
            Instant observedAt,
            Instant asOf) {
        if (integrity == TimelineIntegrity.CORRUPTED_TIMESTAMPS) {
            return EvidenceValue.invalid("corrupted_timeline", "cannot compute overtime from corrupted timestamps");
        }
        return actual.fold(
                knownActual -> promised.fold(
                        knownPromised -> EvidenceValue.known(
                                Math.max(0L, knownActual.value() - knownPromised.value()),
                                SOURCE, observedAt, asOf),
                        unknownPromised -> EvidenceValue.unknown(
                                "MISSING_PROMISED_TIME", unknownPromised.detail()),
                        invalidPromised -> EvidenceValue.invalid(
                                invalidPromised.rawValue(), invalidPromised.violation())),
                unknownActual -> EvidenceValue.unknown("MISSING_ACTUAL_TIME", unknownActual.detail()),
                invalidActual -> EvidenceValue.invalid(invalidActual.rawValue(), invalidActual.violation()));
    }

    public EvidenceValue<Long> dispatchWait() {
        return dispatchWait;
    }

    public EvidenceValue<Long> toShop() {
        return toShop;
    }

    public EvidenceValue<Long> merchantPrep() {
        return merchantPrep;
    }

    public EvidenceValue<Long> onRoad() {
        return onRoad;
    }

    public EvidenceValue<Long> promised() {
        return promised;
    }

    public EvidenceValue<Long> actual() {
        return actual;
    }

    public EvidenceValue<Long> overtimeEvidence() {
        return overtime;
    }

    public EvidenceValue<Boolean> overtimeStatus() {
        return overtime.fold(
                k -> EvidenceValue.known(k.value() > 0L, k.source(), k.observedAt(), k.asOf()),
                u -> EvidenceValue.unknown(u.reasonCode(), u.detail()),
                i -> EvidenceValue.invalid(i.rawValue(), i.violation()));
    }

    public EvidenceValue<ZoneId> zone() {
        return zone;
    }

    public TimelineIntegrity integrity() {
        return integrity;
    }

    public List<String> anomalies() {
        return anomalies;
    }

    public MinuteRoundingMode roundingMode() {
        return roundingMode;
    }

    public boolean complete() {
        return integrity == TimelineIntegrity.COMPLETE_VALID;
    }

    public Optional<Long> overtimeMinutesOrEmpty() {
        return overtime.knownValue();
    }

    /** True only when overtime is affirmatively known to be positive. */
    public boolean overtime() {
        return overtimeStatus().knownValue().orElse(false);
    }

    /**
     * The leg that consumed the largest share of elapsed time when the timeline
     * is valid and complete; reports {@link #LEG_INVALID} or {@link #LEG_UNKNOWN}
     * rather than pretending a missing or reversed timeline had no anomaly.
     */
    public String dominantLeg() {
        if (integrity == TimelineIntegrity.CORRUPTED_TIMESTAMPS) {
            return LEG_INVALID;
        }
        if (!overtime.isKnown()) {
            return LEG_UNKNOWN;
        }
        long overtimeVal = overtime.knownValue().orElse(0L);
        if (overtimeVal == 0L) {
            return complete() ? LEG_NONE : LEG_UNKNOWN;
        }
        if (!dispatchWait.isKnown() || !toShop.isKnown() || !merchantPrep.isKnown() || !onRoad.isKnown()) {
            return LEG_UNKNOWN;
        }
        long dispatch = dispatchWait.knownValue().orElse(0L);
        long toShopVal = toShop.knownValue().orElse(0L);
        long prep = merchantPrep.knownValue().orElse(0L);
        long road = onRoad.knownValue().orElse(0L);
        long max = Math.max(Math.max(dispatch, toShopVal), Math.max(prep, road));
        if (max == 0L) {
            return LEG_NONE;
        }
        if (max == prep) {
            return LEG_MERCHANT_PREP;
        }
        if (max == dispatch) {
            return LEG_DISPATCH_WAIT;
        }
        if (max == road) {
            return LEG_ON_ROAD;
        }
        return LEG_TO_SHOP;
    }

    /** Chinese label for {@link #dominantLeg()}, used by legacy retrieval until PR5. */
    public String dominantLegLabel() {
        return switch (dominantLeg()) {
            case LEG_MERCHANT_PREP -> "商家出餐慢";
            case LEG_DISPATCH_WAIT -> "运力不足";
            case LEG_ON_ROAD -> "路途耗时长";
            case LEG_TO_SHOP -> "骑手到店慢";
            case LEG_NONE -> "无异常";
            case LEG_INVALID -> "时间轴异常";
            default -> "信息不足";
        };
    }

    /** Flat view for prompts, step outputs and evaluation. Missing/invalid fields serialize as null, never 0. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dispatch_wait_minutes", dispatchWait.knownValue().orElse(null));
        map.put("to_shop_minutes", toShop.knownValue().orElse(null));
        map.put("merchant_prep_minutes", merchantPrep.knownValue().orElse(null));
        map.put("on_road_minutes", onRoad.knownValue().orElse(null));
        map.put("promised_minutes", promised.knownValue().orElse(null));
        map.put("actual_minutes", actual.knownValue().orElse(null));
        map.put("overtime_minutes", overtime.knownValue().orElse(null));
        map.put("overtime_known", overtime.isKnown());
        map.put("dominant_leg", dominantLeg());
        map.put("dominant_leg_label", dominantLegLabel());
        map.put("timeline_complete", complete());
        map.put("timeline_integrity", integrity.name());
        map.put("timeline_anomalies", anomalies);
        map.put("zone_id", zone.fold(k -> k.value().getId(), u -> null, EvidenceValue.Invalid::rawValue));
        return map;
    }
}

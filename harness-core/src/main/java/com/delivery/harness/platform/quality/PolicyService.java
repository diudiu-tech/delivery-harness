package com.delivery.harness.platform.quality;

import com.delivery.harness.common.dto.CompensationMethod;
import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PolicyService {
    public static final String KIND = "policy";
    private final PlatformStore store;
    private final RuleBaseService ruleBase;

    @Value("${harness.mode:demo}")
    private String mode = "demo";

    @EventListener(ApplicationReadyEvent.class)
    @Order(-50)
    public void seedDemoDefaults() {
        if ("demo".equals(mode)) {
            ensureDefault("demo");
        }
    }

    public synchronized void ensureDefault(String tenant) {
        if ("pilot".equals(mode)) {
            return;
        }
        if (store.get(tenant, KIND, "policy-default-v1").isPresent()) {
            return;
        }
        List<Map<String, Object>> rules = ruleBase.findAll().stream().map(JsonUtil::toMap).toList();
        rules.forEach(
                rule -> {
                    if ("compensation".equals(rule.get("ruleType"))) {
                        rule.put("content", generatedContent(rule));
                    }
                });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rules", rules);
        data.put("state", "PUBLISHED");
        data.put("created_by", "SYSTEM");
        data.put("published_by", "SYSTEM");
        data.put("effective_from", "2000-01-01T00:00:00Z");
        data.put("currency", "CNY");
        data.put("data_mode", "SYNTHETIC");
        data.put("content_hash", QualityData.hash(rules));
        store.create(tenant, KIND, "policy-default-v1", data, "SYSTEM", "POLICY_SEED");
    }

    public PlatformStore.Entity create(String tenant, String actor, Map<String, Object> request) {
        List<Map<String, Object>> rules = ruleMaps(request.get("rules"));
        if (rules.isEmpty() || rules.size() > 100) {
            throw new IllegalArgumentException("rules must contain 1..100 rules");
        }
        for (Map<String, Object> rule : rules) {
            if ("compensation".equals(rule.get("ruleType"))) {
                Map<String, Object> actions =
                        new LinkedHashMap<>(QualityData.map(rule.get("actions")));
                actions.put("method", CompensationMethod.fromAction(actions).name());
                rule.put("actions", actions);
            }
        }
        Map<String, Object> data = QualityData.copy(request);
        data.put("rules", rules);
        data.put("state", "DRAFT");
        data.put("created_by", actor);
        data.put("currency", QualityData.text(request, "currency", "CNY"));
        if (!"CNY".equals(data.get("currency"))) {
            throw new IllegalArgumentException("Pilot supports CNY only");
        }
        validateTime(data);
        return store.create(
                tenant, KIND, UUID.randomUUID().toString(), data, actor, "POLICY_DRAFT_CREATED");
    }

    public Map<String, Object> validate(String tenant, String id) {
        Map<String, Object> data = store.require(tenant, KIND, id).data();
        List<Map<String, Object>> rules = ruleMaps(data.get("rules"));
        List<Map<String, Object>> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> rule : rules) {
            String ruleId = QualityData.text(rule, "ruleId", "");
            if (ruleId.isBlank() || !ids.add(ruleId)) {
                errors.add(issue(ruleId, "DUPLICATE_OR_MISSING_RULE_ID"));
            }
            String type = QualityData.text(rule, "ruleType", "");
            if (!Set.of("compensation", "analysis").contains(type)) {
                errors.add(issue(ruleId, "UNSUPPORTED_RULE_TYPE"));
            }
            if (!"compensation".equals(type) || Boolean.FALSE.equals(rule.get("enabled"))) {
                continue;
            }
            Map<String, Object> conditions = QualityData.map(rule.get("conditions"));
            Map<String, Object> actions = QualityData.map(rule.get("actions"));
            if (!Set.of("OVERTIME", "DAMAGED", "WRONG_ORDER", "MISSING_ITEM")
                    .contains(QualityData.text(conditions, "complaint_type", ""))) {
                errors.add(issue(ruleId, "INVALID_COMPLAINT_TYPE"));
            }
            if (!Set.of(
                            "complaint_type",
                            "min_overtime_minutes",
                            "max_overtime_minutes",
                            "currency")
                    .containsAll(conditions.keySet())) {
                errors.add(issue(ruleId, "UNSUPPORTED_CONDITION"));
            }
            try {
                int min = bound(conditions.get("min_overtime_minutes"), 0);
                int max = bound(conditions.get("max_overtime_minutes"), Integer.MAX_VALUE);
                if (min < 0 || max < min) {
                    errors.add(issue(ruleId, "INVALID_INTERVAL"));
                }
                String amountType = QualityData.text(actions, "amount_type", "");
                switch (amountType) {
                    case "FIXED" -> nonnegative(actions, "amount", false);
                    case "PERCENT_CAPPED" -> {
                        nonnegative(actions, "rate", true);
                        nonnegative(actions, "cap", false);
                    }
                    case "DAMAGE_LEVEL" -> {
                        nonnegative(actions, "partial_rate", true);
                        nonnegative(actions, "partial_cap", false);
                    }
                    case "MANUAL" -> {}
                    default -> errors.add(issue(ruleId, "UNSUPPORTED_AMOUNT_TYPE"));
                }
                if (QualityData.text(actions, "method", "").isBlank()) {
                    errors.add(issue(ruleId, "MISSING_METHOD"));
                } else {
                    CompensationMethod.fromAction(actions);
                }
            } catch (IllegalArgumentException ex) {
                errors.add(issue(ruleId, "INVALID_AMOUNT_OR_BOUNDARY"));
            }
        }
        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> a = rules.get(i);
            for (int j = i + 1; j < rules.size(); j++) {
                Map<String, Object> b = rules.get(j);
                if (overlap(a, b)) {
                    errors.add(
                            Map.of(
                                    "rule_id",
                                    QualityData.text(a, "ruleId", ""),
                                    "conflicting_rule_id",
                                    QualityData.text(b, "ruleId", ""),
                                    "code",
                                    "OVERLAPPING_RULES"));
                }
            }
        }
        return Map.of(
                "valid",
                errors.isEmpty(),
                "errors",
                errors,
                "coverage_gaps",
                overtimeCoverageGaps(rules),
                "rule_count",
                rules.size(),
                "content_hash",
                QualityData.hash(rules));
    }

    /** Warnings only: uncovered minutes still have no applicable rule and require OUT_OF_SCOPE. */
    private static List<Map<String, Object>> overtimeCoverageGaps(List<Map<String, Object>> rules) {
        List<long[]> intervals = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            if (!"compensation".equals(rule.get("ruleType"))
                    || Boolean.FALSE.equals(rule.get("enabled"))) {
                continue;
            }
            Map<String, Object> conditions = QualityData.map(rule.get("conditions"));
            if (!"OVERTIME".equals(conditions.get("complaint_type"))) {
                continue;
            }
            try {
                int min = bound(conditions.get("min_overtime_minutes"), 0);
                long max =
                        conditions.get("max_overtime_minutes") == null
                                ? Long.MAX_VALUE
                                : bound(conditions.get("max_overtime_minutes"), Integer.MAX_VALUE);
                if (min >= 0 && max >= min) {
                    intervals.add(new long[] {min, max});
                }
            } catch (IllegalArgumentException ignored) {
                // Structural errors are already reported in errors; invalid intervals do not fill
                // gaps.
            }
        }
        intervals.sort(
                Comparator.<long[]>comparingLong(interval -> interval[0])
                        .thenComparingLong(interval -> interval[1]));
        List<Map<String, Object>> gaps = new ArrayList<>();
        long cursor = 0;
        for (long[] interval : intervals) {
            if (interval[0] > cursor) {
                gaps.add(coverageGap(cursor, interval[0] - 1));
            }
            if (interval[1] == Long.MAX_VALUE) {
                return gaps;
            }
            cursor = Math.max(cursor, interval[1] + 1);
        }
        gaps.add(coverageGap(cursor, null));
        return gaps;
    }

    private static Map<String, Object> coverageGap(long min, Long max) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("code", "POLICY_COVERAGE_GAP");
        gap.put("complaint_type", "OVERTIME");
        gap.put("min_overtime_minutes", min);
        gap.put("max_overtime_minutes", max); // null denotes an unbounded upper end.
        gap.put("severity", "WARNING");
        gap.put("uncovered_decision_status", "OUT_OF_SCOPE");
        return gap;
    }

    public PlatformStore.Entity publish(
            String tenant, String id, String actor, long expectedVersion) {
        Map<String, Object> report = validate(tenant, id);
        if (!Boolean.TRUE.equals(report.get("valid"))) {
            throw new IllegalArgumentException(
                    "Policy validation failed: " + JsonUtil.toJson(report.get("errors")));
        }
        return store.transact(
                tx -> {
                    PlatformStore.Entity entity = tx.require(tenant, KIND, id);
                    if (!"DRAFT".equals(entity.data().get("state"))) {
                        throw PlatformException.conflict("Only draft policies can be published");
                    }
                    if (actor.equals(entity.data().get("created_by"))) {
                        throw new PlatformException(
                                403,
                                "SEPARATION_OF_DUTIES",
                                "Policy publisher must differ from policy author");
                    }
                    Map<String, Object> next = QualityData.copy(entity.data());
                    List<Map<String, Object>> rules = ruleMaps(next.get("rules"));
                    rules.forEach(
                            rule -> {
                                if ("compensation".equals(rule.get("ruleType"))) {
                                    Map<String, Object> actions =
                                            QualityData.copy(QualityData.map(rule.get("actions")));
                                    actions.put(
                                            "method",
                                            CompensationMethod.fromAction(actions).name());
                                    rule.put("actions", actions);
                                    rule.put("content", generatedContent(rule));
                                }
                                rule.put("version", 1);
                                rule.put("lifecycle", "PUBLISHED");
                                rule.put("currency", "CNY");
                            });
                    next.put("rules", rules);
                    next.put("state", "PUBLISHED");
                    next.put("published_by", actor);
                    next.put("published_at", Instant.now().toString());
                    next.put("content_hash", QualityData.hash(rules));
                    return tx.update(
                            tenant, KIND, id, expectedVersion, next, actor, "POLICY_PUBLISHED");
                });
    }

    public PlatformStore.Entity withdraw(
            String tenant, String id, String actor, long version, String reason) {
        PlatformStore.Entity entity = store.require(tenant, KIND, id);
        if (!"PUBLISHED".equals(entity.data().get("state")) || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Withdrawal requires published policy and reason");
        }
        Map<String, Object> data = QualityData.copy(entity.data());
        data.put("state", "WITHDRAWN");
        data.put("withdrawal_reason", reason);
        return store.update(tenant, KIND, id, version, data, actor, "POLICY_WITHDRAWN");
    }

    public List<RuleInfo> resolve(String tenant, String id) {
        Map<String, Object> data = store.require(tenant, KIND, id).data();
        if (!"PUBLISHED".equals(data.get("state"))) {
            throw new IllegalArgumentException("Policy must be published: " + id);
        }
        return ruleMaps(data.get("rules")).stream()
                .map(r -> JsonUtil.fromMap(r, RuleInfo.class))
                .toList();
    }

    public static boolean applicable(
            Map<String, Object> data, String city, String station, Instant asOf) {
        List<String> cities = QualityData.strings(data.get("city_codes"));
        List<String> stations = QualityData.strings(data.get("station_ids"));
        return (cities.isEmpty() || cities.contains(city))
                && (stations.isEmpty() || stations.contains(station))
                && (!data.containsKey("effective_from")
                        || !asOf.isBefore(Instant.parse(data.get("effective_from").toString())))
                && (!data.containsKey("effective_to")
                        || asOf.isBefore(Instant.parse(data.get("effective_to").toString())));
    }

    private static void validateTime(Map<String, Object> data) {
        Instant from =
                Instant.parse(QualityData.text(data, "effective_from", "2000-01-01T00:00:00Z"));
        if (data.containsKey("effective_to")
                && !Instant.parse(data.get("effective_to").toString()).isAfter(from)) {
            throw new IllegalArgumentException("effective_to must be after effective_from");
        }
    }

    private static void nonnegative(Map<String, Object> actions, String key, boolean rate) {
        BigDecimal number = new BigDecimal(String.valueOf(actions.get(key)));
        if (number.signum() < 0
                || (rate && number.compareTo(BigDecimal.ONE) > 0)
                || (!rate && number.stripTrailingZeros().scale() > 2)) {
            throw new IllegalArgumentException("Invalid " + key);
        }
    }

    private static boolean overlap(Map<String, Object> a, Map<String, Object> b) {
        if (!"compensation".equals(a.get("ruleType"))
                || !"compensation".equals(b.get("ruleType"))
                || Boolean.FALSE.equals(a.get("enabled"))
                || Boolean.FALSE.equals(b.get("enabled"))) {
            return false;
        }
        Map<String, Object> ca = QualityData.map(a.get("conditions"));
        Map<String, Object> cb = QualityData.map(b.get("conditions"));
        try {
            return ca.get("complaint_type") != null
                    && ca.get("complaint_type").equals(cb.get("complaint_type"))
                    && bound(ca.get("min_overtime_minutes"), 0)
                            <= bound(cb.get("max_overtime_minutes"), Integer.MAX_VALUE)
                    && bound(cb.get("min_overtime_minutes"), 0)
                            <= bound(ca.get("max_overtime_minutes"), Integer.MAX_VALUE);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static int bound(Object value, int fallback) {
        return value == null ? fallback : new BigDecimal(value.toString()).intValueExact();
    }

    private static Map<String, Object> issue(String id, String code) {
        return Map.of("rule_id", id, "code", code);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> ruleMaps(Object value) {
        if (!(value instanceof List<?> rules)) {
            return List.of();
        }
        return rules.stream().map(rule -> QualityData.copy(QualityData.map(rule))).toList();
    }

    public static String generatedContent(Map<String, Object> rule) {
        Map<String, Object> conditions = QualityData.map(rule.get("conditions"));
        Map<String, Object> actions = QualityData.map(rule.get("actions"));
        String interval =
                QualityData.text(conditions, "complaint_type", "")
                        + "；超时分钟区间["
                        + QualityData.text(conditions, "min_overtime_minutes", "0")
                        + ","
                        + QualityData.text(conditions, "max_overtime_minutes", "+∞")
                        + "]（含端点）。";
        String formula =
                switch (QualityData.text(actions, "amount_type", "MANUAL")) {
                    case "FIXED" -> "固定金额 " + actions.get("amount") + " CNY";
                    case "PERCENT_CAPPED" ->
                            "订单金额 × " + actions.get("rate") + "，上限 " + actions.get("cap") + " CNY";
                    case "DAMAGE_LEVEL" ->
                            "核验全部损坏时全额；部分损坏按订单金额 × "
                                    + actions.get("partial_rate")
                                    + "，上限 "
                                    + actions.get("partial_cap")
                                    + " CNY；未核验转人工";
                    default -> "人工判断，金额未确定";
                };
        return interval
                + formula
                + "；方式："
                + actions.get("method")
                + "；需要审批："
                + actions.get("approval_required");
    }
}

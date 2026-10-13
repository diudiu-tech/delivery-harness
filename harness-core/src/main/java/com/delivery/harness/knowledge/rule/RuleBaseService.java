package com.delivery.harness.knowledge.rule;

import com.delivery.harness.common.dto.CompensationMethod;
import com.delivery.harness.common.dto.RuleInfo;
import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.runtime.RuntimeBinding;
import com.delivery.harness.runtime.RuntimeData;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable published rule versions with deterministic selection and retained historical snapshots.
 */
@Service
public class RuleBaseService {
    private final Map<String, RuleInfo> ruleStore = new ConcurrentHashMap<>();
    private final Map<String, RuleInfo> history = new ConcurrentHashMap<>();

    public synchronized void save(RuleInfo supplied) {
        if (supplied == null || supplied.getRuleId() == null || supplied.getRuleId().isBlank()) {
            throw new IllegalArgumentException("rule_id is required");
        }
        RuleInfo rule = copy(supplied);
        if (rule.getVersion() == null) {
            rule.setVersion("1.0.0");
        }
        if (rule.getLifecycle() == null) {
            rule.setLifecycle("PUBLISHED");
        }
        if (rule.getCurrency() == null) {
            rule.setCurrency("CNY");
        }
        if ("compensation".equals(rule.getRuleType()) && rule.getActions() != null) {
            rule.getActions()
                    .put("method", CompensationMethod.fromAction(rule.getActions()).name());
        }
        validate(rule);
        String key = rule.getRuleId() + "@" + rule.getVersion();
        RuleInfo prior = history.get(key);
        if (prior != null
                && "PUBLISHED".equals(prior.getLifecycle())
                && !RuntimeData.hash(JsonUtil.toMap(prior))
                        .equals(RuntimeData.hash(JsonUtil.toMap(rule)))) {
            throw new IllegalArgumentException(
                    "Published policy versions are immutable; create a new version");
        }
        history.put(key, copy(rule));
        ruleStore.put(rule.getRuleId(), rule);
    }

    public Optional<RuleInfo> findById(String ruleId) {
        return Optional.ofNullable(ruleStore.get(ruleId)).map(RuleBaseService::copy);
    }

    public Optional<RuleInfo> findVersion(String id, String version) {
        return Optional.ofNullable(history.get(id + "@" + version)).map(RuleBaseService::copy);
    }

    public List<RuleInfo> findByType(String type) {
        return findByTypeAt(type, Instant.now());
    }

    public List<RuleInfo> findByTypeAt(String type, Instant at) {
        List<RuleInfo> bound = RuntimeBinding.policyRules();
        return (bound == null ? ruleStore.values().stream() : bound.stream())
                .filter(r -> type.equals(r.getRuleType()))
                .filter(r -> !Boolean.FALSE.equals(r.getEnabled()))
                .filter(r -> r.getLifecycle() == null || "PUBLISHED".equals(r.getLifecycle()))
                .filter(r -> r.getEffectiveFrom() == null || !at.isBefore(r.getEffectiveFrom()))
                .filter(r -> r.getEffectiveTo() == null || at.isBefore(r.getEffectiveTo()))
                .sorted(
                        Comparator.<RuleInfo>comparingInt(
                                        r -> r.getPriority() == null ? 0 : r.getPriority())
                                .reversed()
                                .thenComparing(RuleInfo::getRuleId))
                .map(RuleBaseService::copy)
                .toList();
    }

    public List<RuleInfo> findByCategory(String category) {
        return findAll().stream()
                .filter(r -> category.equals(r.getCategory()))
                .filter(r -> !Boolean.FALSE.equals(r.getEnabled()))
                .toList();
    }

    public List<RuleInfo> searchByKeyword(String keyword) {
        String lower = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
        return findAll().stream()
                .filter(
                        r ->
                                (r.getRuleId() + " " + r.getRuleName() + " " + r.getContent())
                                        .toLowerCase(Locale.ROOT)
                                        .contains(lower))
                .toList();
    }

    public List<RuleInfo> findAll() {
        return ruleStore.values().stream()
                .map(RuleBaseService::copy)
                .sorted(Comparator.comparing(RuleInfo::getRuleId))
                .toList();
    }

    public synchronized void deleteById(String id) {
        ruleStore.remove(id);
    }

    public static List<String> publicationConflicts(List<RuleInfo> rules) {
        List<String> conflicts = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            RuleInfo a = rules.get(i);
            for (int j = i + 1; j < rules.size(); j++) {
                RuleInfo b = rules.get(j);
                if (!"compensation".equals(a.getRuleType())
                        || !"compensation".equals(b.getRuleType())
                        || a.getConditions() == null
                        || b.getConditions() == null) {
                    continue;
                }
                Object type = a.getConditions().get("complaint_type");
                if (type != null
                        && type.equals(b.getConditions().get("complaint_type"))
                        && Objects.equals(a.getPriority(), b.getPriority())
                        && overlap(a, b)) {
                    conflicts.add(a.getRuleId() + " overlaps " + b.getRuleId());
                }
            }
        }
        return conflicts;
    }

    private static boolean overlap(RuleInfo a, RuleInfo b) {
        BigDecimal amin = number(a.getConditions().get("min_overtime_minutes"), BigDecimal.ZERO);
        BigDecimal amax =
                number(
                        a.getConditions().get("max_overtime_minutes"),
                        BigDecimal.valueOf(Long.MAX_VALUE));
        BigDecimal bmin = number(b.getConditions().get("min_overtime_minutes"), BigDecimal.ZERO);
        BigDecimal bmax =
                number(
                        b.getConditions().get("max_overtime_minutes"),
                        BigDecimal.valueOf(Long.MAX_VALUE));
        boolean time =
                (a.getEffectiveTo() == null
                                || b.getEffectiveFrom() == null
                                || b.getEffectiveFrom().isBefore(a.getEffectiveTo()))
                        && (b.getEffectiveTo() == null
                                || a.getEffectiveFrom() == null
                                || a.getEffectiveFrom().isBefore(b.getEffectiveTo()));
        return time && amin.compareTo(bmax) <= 0 && bmin.compareTo(amax) <= 0;
    }

    public static void validate(RuleInfo rule) {
        if (!List.of("DRAFT", "VALIDATING", "PENDING_APPROVAL", "PUBLISHED", "RETIRED")
                .contains(rule.getLifecycle())) {
            throw new IllegalArgumentException("Invalid policy lifecycle");
        }
        if (!"CNY".equals(rule.getCurrency())) {
            throw new IllegalArgumentException("Only CNY policies are supported");
        }
        if (rule.getEffectiveFrom() != null
                && rule.getEffectiveTo() != null
                && !rule.getEffectiveFrom().isBefore(rule.getEffectiveTo())) {
            throw new IllegalArgumentException("Invalid policy effective interval");
        }
        if (!"compensation".equals(rule.getRuleType())) {
            return;
        }
        Map<String, Object> c = rule.getConditions();
        Map<String, Object> a = rule.getActions();
        if (c == null
                || a == null
                || !List.of("OVERTIME", "DAMAGED", "WRONG_ORDER", "MISSING_ITEM")
                        .contains(c.get("complaint_type"))) {
            throw new IllegalArgumentException("Invalid compensation conditions");
        }
        CompensationMethod.fromAction(a);
        BigDecimal min = number(c.get("min_overtime_minutes"), BigDecimal.ZERO);
        BigDecimal max = number(c.get("max_overtime_minutes"), BigDecimal.valueOf(Long.MAX_VALUE));
        if (min.signum() < 0 || min.scale() > 0 || max.scale() > 0 || max.compareTo(min) < 0) {
            throw new IllegalArgumentException("Invalid minute range");
        }
        if (!List.of("FIXED", "PERCENT_CAPPED", "DAMAGE_LEVEL", "MANUAL")
                .contains(a.get("amount_type"))) {
            throw new IllegalArgumentException("Invalid amount_type");
        }
        for (String field : List.of("amount", "cap", "partial_cap", "rate", "partial_rate")) {
            if (a.containsKey(field)) {
                BigDecimal value = number(a.get(field), null);
                if (value == null
                        || value.signum() < 0
                        || (field.contains("rate") && value.compareTo(BigDecimal.ONE) > 0)
                        || (!field.contains("rate") && value.stripTrailingZeros().scale() > 2)) {
                    throw new IllegalArgumentException("Invalid policy value: " + field);
                }
            }
        }
        for (String required :
                switch (String.valueOf(a.get("amount_type"))) {
                    case "FIXED" -> List.of("amount");
                    case "PERCENT_CAPPED" -> List.of("rate", "cap");
                    case "DAMAGE_LEVEL" -> List.of("partial_rate", "partial_cap");
                    default -> List.<String>of();
                }) {
            if (!a.containsKey(required)) {
                throw new IllegalArgumentException("Missing action: " + required);
            }
        }
    }

    private static BigDecimal number(Object raw, BigDecimal fallback) {
        return raw == null ? fallback : new BigDecimal(raw.toString());
    }

    private static RuleInfo copy(RuleInfo rule) {
        return JsonUtil.fromMap(JsonUtil.toMap(rule), RuleInfo.class);
    }
}

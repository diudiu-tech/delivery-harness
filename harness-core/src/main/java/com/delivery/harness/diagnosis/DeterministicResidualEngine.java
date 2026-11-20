package com.delivery.harness.diagnosis;

import com.delivery.harness.common.evidence.EvidenceType;
import com.delivery.harness.common.evidence.EvidenceValue;
import com.delivery.harness.evidence.EvidenceItem;
import com.delivery.harness.evidence.EvidenceSnapshot;
import com.delivery.harness.policy.PolicyBundle;
import com.delivery.harness.policy.PolicyBundleLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure deterministic residual attribution engine replacing LLM arithmetic over
 * structured telemetry.
 *
 * <p>Uses the locked thresholds and normal-state SLA baselines from
 * {@link PolicyBundle.AttributionThresholds} (ADR-0007). Positive excesses across
 * legs do not necessarily sum to {@code overtimeMinutes} because promised
 * duration contains buffer and legs may have negative residuals.
 */
@Service
@RequiredArgsConstructor
public class DeterministicResidualEngine {

    private static final String SOURCE = "residual_engine";

    private final PolicyBundleLoader policyBundleLoader;

    public ResidualDiagnosis diagnose(EvidenceSnapshot snapshot) {
        return diagnose(snapshot, policyBundleLoader.activeBundle());
    }

    public ResidualDiagnosis diagnose(EvidenceSnapshot snapshot, PolicyBundle bundle) {
        PolicyBundle.AttributionThresholds thresholds = bundle.attributionThresholds();
        EvidenceValue<Long> overtime = readLong(snapshot, EvidenceType.TIMELINE_OVERTIME_MINUTES);

        FactorEvaluation dispatch = evaluateDispatch(snapshot, thresholds);
        FactorEvaluation toShop = evaluateToShop(snapshot, thresholds);
        FactorEvaluation prep = evaluateMerchantPrep(snapshot, thresholds);
        FactorEvaluation weather = evaluateWeather(snapshot, thresholds);
        FactorEvaluation road = evaluateUnexplainedRoad(snapshot, thresholds);
        List<FactorEvaluation> allFactors = List.of(dispatch, toShop, prep, weather, road);

        long sumExcess = allFactors.stream().mapToLong(FactorEvaluation::excessMinutes).sum();
        Set<String> gaps = new LinkedHashSet<>();

        boolean anyInvalid = overtime.isInvalid()
                || allFactors.stream().anyMatch(f -> f.rawDeltaMinutes().isInvalid());
        if (anyInvalid) {
            gaps.add("时间轴或关键证据存在异常/倒序，无法给出确定性归因结论");
            collectFactorGaps(allFactors, gaps);
            return new ResidualDiagnosis(
                    DiagnosisOutcome.UNDETERMINED,
                    null,
                    List.of(),
                    allFactors,
                    overtime,
                    sumExcess,
                    List.copyOf(gaps),
                    "时间轴数据异常，无法进行确定性残差归因，需人工核查原始事件。");
        }

        if (overtime.isUnknown()) {
            gaps.add("缺少承诺或实际送达时间，无法判定是否超时及延误分布");
            collectFactorGaps(allFactors, gaps);
            return new ResidualDiagnosis(
                    DiagnosisOutcome.UNDETERMINED,
                    null,
                    List.of(),
                    allFactors,
                    overtime,
                    sumExcess,
                    List.copyOf(gaps),
                    "缺少关键时间节点，无法确定是否超时，归因待定。");
        }

        long overtimeVal = overtime.knownValue().orElse(0L);
        if (overtimeVal == 0L) {
            return new ResidualDiagnosis(
                    DiagnosisOutcome.NO_OVERTIME_ANOMALY,
                    null,
                    List.of(),
                    allFactors,
                    overtime,
                    sumExcess,
                    List.of(),
                    "订单实际送达未超过承诺时间（超时 0 分钟），无超时异常。");
        }

        List<FactorEvaluation> significant = allFactors.stream()
                .filter(FactorEvaluation::exceedsThreshold)
                .sorted(Comparator.comparingLong(FactorEvaluation::excessMinutes).reversed()
                        .thenComparingInt(f -> f.code().ordinal()))
                .toList();

        collectSignificantGaps(significant, gaps);

        if (significant.isEmpty()) {
            gaps.add("各环节单段超额均未达到阈值（" + thresholds.minExcessMinutes()
                    + " 分钟），可能为多段轻微累加或承诺余量偏紧");
            return new ResidualDiagnosis(
                    DiagnosisOutcome.UNDETERMINED,
                    null,
                    List.of(),
                    allFactors,
                    overtime,
                    sumExcess,
                    List.copyOf(gaps),
                    "订单超时 " + overtimeVal + " 分钟，但无单一环节超额达到 "
                            + thresholds.minExcessMinutes() + " 分钟阈值，归因待定。");
        }

        List<CausalFactorCode> significantCodes = significant.stream().map(FactorEvaluation::code).toList();
        if (significant.size() == 1) {
            FactorEvaluation top = significant.get(0);
            return new ResidualDiagnosis(
                    DiagnosisOutcome.SINGLE_PRIMARY_CANDIDATE,
                    top.code(),
                    significantCodes,
                    allFactors,
                    overtime,
                    sumExcess,
                    List.copyOf(gaps),
                    "订单超时 " + overtimeVal + " 分钟，唯一显著候选因子为「" + top.label()
                            + "」（超额 +" + top.excessMinutes() + " 分钟）。");
        }

        FactorEvaluation top1 = significant.get(0);
        FactorEvaluation top2 = significant.get(1);
        long lead = top1.excessMinutes() - top2.excessMinutes();
        if (lead >= thresholds.dominanceMarginMinutes()) {
            return new ResidualDiagnosis(
                    DiagnosisOutcome.SINGLE_PRIMARY_CANDIDATE,
                    top1.code(),
                    significantCodes,
                    allFactors,
                    overtime,
                    sumExcess,
                    List.copyOf(gaps),
                    "订单超时 " + overtimeVal + " 分钟，首要候选因子为「" + top1.label()
                            + "」（超额 +" + top1.excessMinutes() + " 分钟，领先次要因子「"
                            + top2.label() + "」" + lead + " 分钟）。");
        }

        String names = significant.stream()
                .map(f -> f.label() + "(+" + f.excessMinutes() + "m)")
                .collect(Collectors.joining("、"));
        return new ResidualDiagnosis(
                DiagnosisOutcome.MULTI_FACTOR,
                null,
                significantCodes,
                allFactors,
                overtime,
                sumExcess,
                List.copyOf(gaps),
                "订单超时 " + overtimeVal + " 分钟，存在多个并列显著候选因子（领先差 " + lead
                        + " 分钟小于 " + thresholds.dominanceMarginMinutes()
                        + " 分钟门槛）：" + names + "。");
    }

    private static FactorEvaluation evaluateDispatch(
            EvidenceSnapshot snapshot, PolicyBundle.AttributionThresholds thresholds) {
        EvidenceValue<Long> actual = readLong(snapshot, EvidenceType.TIMELINE_DISPATCH_WAIT_MINUTES);
        EvidenceValue<Long> delta = subtractBaseline(actual, thresholds.normalDispatchBaselineMinutes());
        long excess = positiveExcess(delta);
        boolean exceeds = excess >= thresholds.minExcessMinutes();

        List<String> support = new ArrayList<>();
        List<String> contradict = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        if (actual.isKnown()) {
            addIdIfPresent(snapshot, EvidenceType.TIMELINE_DISPATCH_WAIT_MINUTES, support);
        } else {
            gaps.add("缺少派单等待耗时数据");
        }

        EvidenceValue<Double> capRatio = readDouble(snapshot, EvidenceType.STATION_CAPACITY_RATIO);
        capRatio.fold(
                known -> {
                    if (known.value() >= thresholds.strainedCapacityRatio()) {
                        addIdIfPresent(snapshot, EvidenceType.STATION_CAPACITY_RATIO, support);
                        addIdIfPresent(snapshot, EvidenceType.STATION_AVG_DISPATCH_WAIT_MINUTES, support);
                    } else if (exceeds) {
                        addIdIfPresent(snapshot, EvidenceType.STATION_CAPACITY_RATIO, contradict);
                        gaps.add("派单耗时偏长但站点运力比未达紧张阈值（" + thresholds.strainedCapacityRatio()
                                + "），需核实派单策略或特殊区域限制");
                    }
                    return null;
                },
                unknown -> {
                    if (exceeds) {
                        gaps.add("缺少站点历史运力快照，无法佐证是否运力不足");
                    }
                    return null;
                },
                invalid -> {
                    gaps.add("站点运力比数据异常");
                    return null;
                });

        return new FactorEvaluation(
                CausalFactorCode.DISPATCH_WAIT_EXCESS,
                CausalFactorCode.DISPATCH_WAIT_EXCESS.label(),
                delta, excess, exceeds, List.copyOf(support), List.copyOf(contradict), List.copyOf(gaps));
    }

    private static FactorEvaluation evaluateToShop(
            EvidenceSnapshot snapshot, PolicyBundle.AttributionThresholds thresholds) {
        EvidenceValue<Long> actual = readLong(snapshot, EvidenceType.TIMELINE_TO_SHOP_MINUTES);
        EvidenceValue<Long> delta = subtractBaseline(actual, thresholds.toShopBaselineMinutes());
        long excess = positiveExcess(delta);
        boolean exceeds = excess >= thresholds.minExcessMinutes();

        List<String> support = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        if (actual.isKnown()) {
            addIdIfPresent(snapshot, EvidenceType.TIMELINE_TO_SHOP_MINUTES, support);
        } else {
            gaps.add("缺少骑手到店耗时数据");
        }
        if (exceeds) {
            gaps.add("缺少骑手接单时实时位置与并单记录");
        }

        return new FactorEvaluation(
                CausalFactorCode.TO_SHOP_EXCESS,
                CausalFactorCode.TO_SHOP_EXCESS.label(),
                delta, excess, exceeds, List.copyOf(support), List.of(), List.copyOf(gaps));
    }

    private static FactorEvaluation evaluateMerchantPrep(
            EvidenceSnapshot snapshot, PolicyBundle.AttributionThresholds thresholds) {
        EvidenceValue<Long> actual = readLong(snapshot, EvidenceType.TIMELINE_MERCHANT_PREP_MINUTES);
        EvidenceValue<Long> delta = subtractBaseline(actual, thresholds.normalMerchantPrepBaselineMinutes());
        long excess = positiveExcess(delta);
        boolean exceeds = excess >= thresholds.minExcessMinutes();

        List<String> support = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        if (actual.isKnown()) {
            addIdIfPresent(snapshot, EvidenceType.TIMELINE_MERCHANT_PREP_MINUTES, support);
            addIdIfPresent(snapshot, EvidenceType.STATION_AVG_MERCHANT_PREP_MINUTES, support);
        } else {
            gaps.add("缺少商家备餐等待耗时数据");
        }

        return new FactorEvaluation(
                CausalFactorCode.MERCHANT_PREP_EXCESS,
                CausalFactorCode.MERCHANT_PREP_EXCESS.label(),
                delta, excess, exceeds, List.copyOf(support), List.of(), List.copyOf(gaps));
    }

    private static FactorEvaluation evaluateWeather(
            EvidenceSnapshot snapshot, PolicyBundle.AttributionThresholds thresholds) {
        EvidenceValue<Long> clearEta = readLong(snapshot, EvidenceType.ETA_CLEAR_MINUTES);
        EvidenceValue<Long> weatherEta = readLong(snapshot, EvidenceType.ETA_WEATHER_MINUTES);
        EvidenceValue<Long> delta = subtractValues(weatherEta, clearEta);
        long excess = positiveExcess(delta);
        boolean exceeds = excess >= thresholds.minExcessMinutes();

        List<String> support = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        if (delta.isKnown()) {
            addIdIfPresent(snapshot, EvidenceType.ETA_WEATHER_MINUTES, support);
            addIdIfPresent(snapshot, EvidenceType.ETA_CLEAR_MINUTES, support);
            addIdIfPresent(snapshot, EvidenceType.WEATHER_CONDITION, support);
        } else {
            gaps.add("缺少天气或 ETA 基准数据，无法估算天气增量");
        }

        return new FactorEvaluation(
                CausalFactorCode.WEATHER_EXPLAINED,
                CausalFactorCode.WEATHER_EXPLAINED.label(),
                delta, excess, exceeds, List.copyOf(support), List.of(), List.copyOf(gaps));
    }

    private static FactorEvaluation evaluateUnexplainedRoad(
            EvidenceSnapshot snapshot, PolicyBundle.AttributionThresholds thresholds) {
        EvidenceValue<Long> onRoad = readLong(snapshot, EvidenceType.TIMELINE_ON_ROAD_MINUTES);
        EvidenceValue<Long> weatherEta = readLong(snapshot, EvidenceType.ETA_WEATHER_MINUTES);
        EvidenceValue<Long> delta = subtractValues(onRoad, weatherEta);
        long excess = positiveExcess(delta);
        boolean exceeds = excess >= thresholds.minExcessMinutes();

        List<String> support = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        if (delta.isKnown()) {
            addIdIfPresent(snapshot, EvidenceType.TIMELINE_ON_ROAD_MINUTES, support);
            addIdIfPresent(snapshot, EvidenceType.ETA_WEATHER_MINUTES, support);
        } else {
            gaps.add("缺少路途实际耗时或天气 ETA 基准数据");
        }
        if (exceeds) {
            gaps.add("缺少骑手 GPS 轨迹与并单记录，仅记录为路途未解释超额，不可自动定责骑手");
        }

        return new FactorEvaluation(
                CausalFactorCode.UNEXPLAINED_ROAD_EXCESS,
                CausalFactorCode.UNEXPLAINED_ROAD_EXCESS.label(),
                delta, excess, exceeds, List.copyOf(support), List.of(), List.copyOf(gaps));
    }

    private static EvidenceValue<Long> subtractBaseline(EvidenceValue<Long> actual, long baseline) {
        return actual.fold(
                known -> EvidenceValue.known(known.value() - baseline, SOURCE, known.observedAt(), known.asOf()),
                unknown -> EvidenceValue.unknown(unknown.reasonCode(), unknown.detail()),
                invalid -> EvidenceValue.invalid(invalid.rawValue(), invalid.violation()));
    }

    private static EvidenceValue<Long> subtractValues(EvidenceValue<Long> left, EvidenceValue<Long> right) {
        return left.fold(
                kLeft -> right.fold(
                        kRight -> EvidenceValue.known(
                                kLeft.value() - kRight.value(), SOURCE, kLeft.observedAt(), kLeft.asOf()),
                        uRight -> EvidenceValue.unknown(uRight.reasonCode(), uRight.detail()),
                        iRight -> EvidenceValue.invalid(iRight.rawValue(), iRight.violation())),
                uLeft -> EvidenceValue.unknown(uLeft.reasonCode(), uLeft.detail()),
                iLeft -> EvidenceValue.invalid(iLeft.rawValue(), iLeft.violation()));
    }

    private static long positiveExcess(EvidenceValue<Long> delta) {
        return delta.knownValue().map(v -> Math.max(0L, v)).orElse(0L);
    }

    private static EvidenceValue<Long> readLong(EvidenceSnapshot snapshot, EvidenceType type) {
        Optional<EvidenceItem> item = snapshot.latestByType(type);
        if (item.isEmpty()) {
            return EvidenceValue.unknown("MISSING_EVIDENCE", type.name() + " not present in snapshot");
        }
        return item.get().value().fold(
                known -> {
                    if (known.value() instanceof Number number) {
                        return EvidenceValue.known(number.longValue(), known.source(),
                                known.observedAt(), known.asOf());
                    }
                    return EvidenceValue.invalid(String.valueOf(known.value()), type.name() + " is not numeric");
                },
                unknown -> EvidenceValue.unknown(unknown.reasonCode(), unknown.detail()),
                invalid -> EvidenceValue.invalid(invalid.rawValue(), invalid.violation()));
    }

    private static EvidenceValue<Double> readDouble(EvidenceSnapshot snapshot, EvidenceType type) {
        Optional<EvidenceItem> item = snapshot.latestByType(type);
        if (item.isEmpty()) {
            return EvidenceValue.unknown("MISSING_EVIDENCE", type.name() + " not present in snapshot");
        }
        return item.get().value().fold(
                known -> {
                    if (known.value() instanceof Number number) {
                        return EvidenceValue.known(number.doubleValue(), known.source(),
                                known.observedAt(), known.asOf());
                    }
                    return EvidenceValue.invalid(String.valueOf(known.value()), type.name() + " is not numeric");
                },
                unknown -> EvidenceValue.unknown(unknown.reasonCode(), unknown.detail()),
                invalid -> EvidenceValue.invalid(invalid.rawValue(), invalid.violation()));
    }

    private static void addIdIfPresent(EvidenceSnapshot snapshot, EvidenceType type, List<String> target) {
        snapshot.latestByType(type).ifPresent(item -> {
            if (!target.contains(item.evidenceId())) {
                target.add(item.evidenceId());
            }
        });
    }

    private static void collectFactorGaps(List<FactorEvaluation> factors, Set<String> target) {
        for (FactorEvaluation f : factors) {
            target.addAll(f.evidenceGaps());
        }
    }

    private static void collectSignificantGaps(List<FactorEvaluation> significant, Set<String> target) {
        for (FactorEvaluation f : significant) {
            target.addAll(f.evidenceGaps());
        }
    }
}

package com.delivery.harness.application;

import com.delivery.harness.cases.AssessmentRun;
import com.delivery.harness.cases.CaseStatus;
import com.delivery.harness.cases.CaseStore;
import com.delivery.harness.cases.DecisionAction;
import com.delivery.harness.cases.DeliveryCase;
import com.delivery.harness.cases.HumanDecision;
import com.delivery.harness.cases.RunHealth;
import com.delivery.harness.common.evidence.EvidenceType;
import com.delivery.harness.common.evidence.EvidenceValue;
import com.delivery.harness.common.exception.BizException;
import com.delivery.harness.common.exception.ConflictException;
import com.delivery.harness.common.exception.ResourceNotFoundException;
import com.delivery.harness.common.util.TraceUtil;
import com.delivery.harness.diagnosis.DeterministicResidualEngine;
import com.delivery.harness.diagnosis.ResidualDiagnosis;
import com.delivery.harness.evidence.EvidenceItem;
import com.delivery.harness.evidence.EvidenceSnapshot;
import com.delivery.harness.evidence.EvidenceSource;
import com.delivery.harness.observe.metrics.MetricsService;
import com.delivery.harness.observe.trace.TraceService;
import com.delivery.harness.policy.AdjudicationStatus;
import com.delivery.harness.policy.AttestableEvidenceType;
import com.delivery.harness.policy.ComplaintType;
import com.delivery.harness.policy.DamageLevel;
import com.delivery.harness.policy.PolicyBundle;
import com.delivery.harness.policy.PolicyBundleLoader;
import com.delivery.harness.policy.RoutingAction;
import com.delivery.harness.policy.SingleSourcePolicyEngine;
import com.delivery.harness.template.CustomerDraft;
import com.delivery.harness.template.DeterministicCustomerTemplateRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Application orchestration service owning case lifecycle transitions,
 * idempotency, snapshot collection, deterministic assessment, and tracing.
 *
 * <p>HTTP controllers must delegate here rather than accessing
 * {@link EvidenceSource}, {@link CaseStore}, or {@link SingleSourcePolicyEngine}
 * directly.
 */
@Slf4j
@Service
public class CaseApplicationService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final EvidenceSource evidenceSource;
    private final CaseStore caseStore;
    private final PolicyBundleLoader policyBundleLoader;
    private final SingleSourcePolicyEngine policyEngine;
    private final DeterministicResidualEngine residualEngine;
    private final DeterministicCustomerTemplateRenderer templateRenderer;
    private final TraceService traceService;
    private final MetricsService metricsService;
    private final Clock clock;
    private final UnstructuredExtractionPort extractionPort;

    @Autowired
    public CaseApplicationService(
            EvidenceSource evidenceSource,
            CaseStore caseStore,
            PolicyBundleLoader policyBundleLoader,
            SingleSourcePolicyEngine policyEngine,
            DeterministicResidualEngine residualEngine,
            DeterministicCustomerTemplateRenderer templateRenderer,
            TraceService traceService,
            MetricsService metricsService,
            Clock clock,
            @Autowired(required = false) UnstructuredExtractionPort extractionPort) {
        this.evidenceSource = evidenceSource;
        this.caseStore = caseStore;
        this.policyBundleLoader = policyBundleLoader;
        this.policyEngine = policyEngine;
        this.residualEngine = residualEngine;
        this.templateRenderer = templateRenderer;
        this.traceService = traceService;
        this.metricsService = metricsService;
        this.clock = clock;
        this.extractionPort = extractionPort != null
                ? extractionPort : snapshot -> UnstructuredExtractionPort.ExtractionOutcome.empty();
    }

    public CreateCaseResult createCase(CreateCaseCommand command) {
        String idempotencyKey = normalizeBlank(command.idempotencyKey());
        if (idempotencyKey != null) {
            Optional<DeliveryCase> existing = caseStore.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return new CreateCaseResult(existing.get(), true);
            }
        }

        ComplaintType complaintType = ComplaintType.parse(command.complaintType())
                .orElseThrow(() -> new BizException(400, "Unsupported complaint_type: " + command.complaintType()));

        Optional<DeliveryCase> openDuplicate = caseStore.findOpenByOrderAndComplaint(
                command.orderId(), complaintType);
        if (openDuplicate.isPresent()) {
            throw new ConflictException("An open case already exists for order " + command.orderId()
                    + " and complaint " + complaintType.name() + ": " + openDuplicate.get().caseId());
        }

        EvidenceSnapshot snapshot = evidenceSource.collect(command.orderId())
                .orElseThrow(() -> {
                    metricsService.incrementCounter("case_create_not_found");
                    return new ResourceNotFoundException("Order not found");
                });

        Instant now = clock.instant();
        String caseId = "CASE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        AssessmentRun run = executeRun(caseId, 1, complaintType, snapshot);
        CaseStatus initialStatus = initialStatusFor(run);

        DeliveryCase created = new DeliveryCase(
                caseId,
                command.orderId(),
                complaintType,
                initialStatus,
                1,
                idempotencyKey,
                now,
                now,
                snapshot,
                List.of(run),
                List.of(),
                null,
                null,
                run.customerDraft());

        caseStore.save(created);
        metricsService.incrementCounter("case_created");
        return new CreateCaseResult(created, false);
    }

    public List<DeliveryCase> listCases(int limit) {
        return caseStore.listRecent(limit);
    }

    public DeliveryCase getCase(String caseId) {
        return caseStore.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("Case not found"));
    }

    public DeliveryCase attestEvidence(String caseId, AttestCommand command) {
        DeliveryCase current = getCase(caseId);
        verifyCaseVersion(current, command.expectedCaseVersion());

        if (current.status() != CaseStatus.WAITING_EVIDENCE
                && current.status() != CaseStatus.PENDING_DECISION
                && current.status() != CaseStatus.ESCALATED) {
            throw new ConflictException("Cannot attest evidence when case status is " + current.status());
        }

        AttestableEvidenceType attestableType = parseAttestableType(command.evidenceType());
        Object typedValue = parseAttestableValue(attestableType, command.value());
        String operatorId = requireNonBlank(command.operatorId(), "operatorId is required");

        Instant now = clock.instant();
        long humanCount = current.latestSnapshot().items().keySet().stream()
                .filter(id -> id.startsWith("EV-HUMAN-"))
                .count();
        String evidenceId = String.format("EV-HUMAN-%02d", humanCount + 1);
        EvidenceValue.Known<?> attestedValue = EvidenceValue.known(
                typedValue, "human:" + operatorId, now, current.latestSnapshot().asOf());

        EvidenceSnapshot updatedSnapshot = current.latestSnapshot().withHumanAttestation(
                evidenceId, attestableType.evidenceType(), attestedValue, now);

        int nextRunNumber = current.runs().size() + 1;
        AssessmentRun newRun = executeRun(caseId, nextRunNumber, current.complaintType(), updatedSnapshot);
        CaseStatus nextStatus = initialStatusFor(newRun);

        List<AssessmentRun> updatedRuns = new ArrayList<>(current.runs());
        updatedRuns.add(newRun);

        DeliveryCase updated = new DeliveryCase(
                current.caseId(),
                current.orderId(),
                current.complaintType(),
                nextStatus,
                current.version() + 1,
                current.idempotencyKey(),
                current.createdAt(),
                now,
                updatedSnapshot,
                List.copyOf(updatedRuns),
                current.decisions(),
                null,
                current.executionReceiptId(),
                newRun.customerDraft());

        caseStore.save(updated);
        metricsService.incrementCounter("case_attested");
        return updated;
    }

    public DeliveryCase submitDecision(String caseId, DecisionCommand command) {
        DeliveryCase current = getCase(caseId);
        verifyCaseVersion(current, command.expectedCaseVersion());

        AssessmentRun latestRun = current.latestRun();
        if (command.runId() == null || !command.runId().equals(latestRun.runId())) {
            throw new ConflictException("Stale runId: latest run is " + latestRun.runId());
        }

        DecisionAction action = command.action();
        if (action == null) {
            throw new BizException(400, "action is required");
        }
        String operatorId = requireNonBlank(command.operatorId(), "operatorId is required");
        Instant now = clock.instant();
        PolicyBundle.Limits limits = policyBundleLoader.activeBundle().limits();

        CaseStatus nextStatus = current.status();
        BigDecimal nextPendingOverride = current.pendingOverrideAmount();
        String nextReceiptId = current.executionReceiptId();
        CustomerDraft nextDraft = current.latestCustomerDraft();
        EvidenceSnapshot nextSnapshot = current.latestSnapshot();
        List<AssessmentRun> nextRuns = new ArrayList<>(current.runs());

        switch (action) {
            case REQUEST_EVIDENCE -> {
                requireStatus(current, CaseStatus.PENDING_DECISION, CaseStatus.ESCALATED);
                nextStatus = CaseStatus.WAITING_EVIDENCE;
            }
            case ACCEPT -> {
                requireStatus(current, CaseStatus.PENDING_DECISION);
                SingleSourcePolicyEngine.AdjudicationResult adj = latestRun.adjudication();
                boolean needsPayout = adj.status() == AdjudicationStatus.ELIGIBLE
                        && adj.actionableAmount() != null
                        && adj.actionableAmount().compareTo(ZERO) > 0;
                nextStatus = needsPayout ? CaseStatus.APPROVED_PENDING_EXECUTION : CaseStatus.CLOSED;
                nextPendingOverride = null;
            }
            case OVERRIDE -> {
                requireStatus(current, CaseStatus.PENDING_DECISION);
                requireNonBlank(command.overrideReasonCode(), "overrideReasonCode is required for OVERRIDE");
                BigDecimal overrideAmount = command.finalAmount();
                if (overrideAmount != null && overrideAmount.compareTo(ZERO) < 0) {
                    throw new BizException(400, "finalAmount must be >= 0");
                }
                boolean exceedsApprovalOrCap = overrideAmount != null
                        && (overrideAmount.compareTo(limits.approvalThreshold()) >= 0
                        || overrideAmount.compareTo(limits.escalationCap()) > 0);
                if (exceedsApprovalOrCap) {
                    nextStatus = CaseStatus.ESCALATED;
                    nextPendingOverride = overrideAmount;
                } else if (overrideAmount != null && overrideAmount.compareTo(ZERO) > 0) {
                    nextStatus = CaseStatus.APPROVED_PENDING_EXECUTION;
                    nextPendingOverride = overrideAmount;
                } else {
                    nextStatus = CaseStatus.CLOSED;
                    nextPendingOverride = null;
                }
            }
            case ESCALATE -> {
                requireStatus(current, CaseStatus.WAITING_EVIDENCE, CaseStatus.PENDING_DECISION);
                nextStatus = CaseStatus.ESCALATED;
            }
            case RETURN_TO_REVIEW -> {
                requireStatus(current, CaseStatus.ESCALATED);
                nextStatus = CaseStatus.PENDING_DECISION;
            }
            case APPROVE -> {
                requireStatus(current, CaseStatus.ESCALATED);
                BigDecimal effectiveAmount = resolveAmountToApprove(current, latestRun, command.finalAmount());
                if (effectiveAmount != null && effectiveAmount.compareTo(limits.escalationCap()) > 0) {
                    requireNonBlank(command.exceptionReason(),
                            "Approving an amount above escalationCap ("
                                    + limits.escalationCap().toPlainString() + ") requires exceptionReason");
                }
                nextStatus = CaseStatus.APPROVED_PENDING_EXECUTION;
            }
            case REJECT_CLOSE -> {
                requireStatus(current, CaseStatus.ESCALATED);
                nextStatus = CaseStatus.CLOSED;
                nextPendingOverride = null;
            }
            case CLOSE -> {
                requireStatus(current, CaseStatus.WAITING_EVIDENCE, CaseStatus.PENDING_DECISION);
                nextStatus = CaseStatus.CLOSED;
                nextPendingOverride = null;
            }
            case RECORD_EXECUTION_RECEIPT -> {
                requireStatus(current, CaseStatus.APPROVED_PENDING_EXECUTION);
                String receiptId = requireNonBlank(command.externalReceiptId(),
                        "externalReceiptId is required for RECORD_EXECUTION_RECEIPT");
                nextReceiptId = receiptId;
                nextDraft = templateRenderer.render(latestRun.adjudication(), true, receiptId);
                nextStatus = CaseStatus.CLOSED;
            }
            case REOPEN -> {
                requireStatus(current, CaseStatus.CLOSED);
                nextReceiptId = null;
                nextPendingOverride = null;
                nextSnapshot = evidenceSource.collect(current.orderId()).orElse(current.latestSnapshot());
                AssessmentRun reopenedRun = executeRun(
                        caseId, nextRuns.size() + 1, current.complaintType(), nextSnapshot);
                nextRuns.add(reopenedRun);
                nextDraft = reopenedRun.customerDraft();
                nextStatus = initialStatusFor(reopenedRun);
            }
        }

        HumanDecision decision = new HumanDecision(
                "DEC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                latestRun.runId(),
                action,
                operatorId,
                HumanDecision.UNAUTHENTICATED_OPERATOR_MODE,
                normalizeBlank(command.overrideReasonCode()),
                command.finalAmount(),
                normalizeBlank(command.finalMethod()),
                normalizeBlank(command.finalLiability()),
                normalizeBlank(command.exceptionReason()),
                normalizeBlank(command.externalReceiptId()),
                normalizeBlank(command.note()),
                now);

        List<HumanDecision> updatedDecisions = new ArrayList<>(current.decisions());
        updatedDecisions.add(decision);

        DeliveryCase updated = new DeliveryCase(
                current.caseId(),
                current.orderId(),
                current.complaintType(),
                nextStatus,
                current.version() + 1,
                current.idempotencyKey(),
                current.createdAt(),
                now,
                nextSnapshot,
                List.copyOf(nextRuns),
                List.copyOf(updatedDecisions),
                nextPendingOverride,
                nextReceiptId,
                nextDraft);

        caseStore.save(updated);
        metricsService.incrementCounter("case_decision_" + action.name().toLowerCase());
        return updated;
    }

    public PolicySummaryView getActivePolicy() {
        PolicyBundle bundle = policyBundleLoader.activeBundle();
        List<Map<String, Object>> renderedRules = bundle.compensationRules().stream()
                .map(rule -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("rule_id", rule.ruleId());
                    row.put("rule_name", rule.ruleName());
                    row.put("complaint_type", rule.complaintType().name());
                    row.put("priority", rule.priority());
                    row.put("required_evidence", rule.requiredEvidence());
                    row.put("amount_type", rule.amountType().name());
                    row.put("approval_required", rule.approvalRequired());
                    row.put("rendered_description", rule.renderDescription());
                    return row;
                })
                .toList();
        return new PolicySummaryView(bundle, policyBundleLoader.activePolicyHash(), renderedRules);
    }

    public List<EvidenceSource.FixtureSummary> listDemoFixtures() {
        return evidenceSource.listAvailableFixtures();
    }

    private AssessmentRun executeRun(
            String caseId, int runNumber, ComplaintType complaintType, EvidenceSnapshot snapshot) {
        long startNanos = System.nanoTime();
        String traceId = ensureTraceId();
        PolicyBundle bundle = policyBundleLoader.activeBundle();
        String policyHash = policyBundleLoader.activePolicyHash();

        List<TraceService.TraceSpan> spans = new ArrayList<>();

        long diagStart = System.nanoTime();
        ResidualDiagnosis diagnosis = residualEngine.diagnose(snapshot, bundle);
        spans.add(span("diagnosis", "deterministic_residual_engine", elapsedMs(diagStart),
                Map.of("outcome", diagnosis.outcome().name())));

        long policyStart = System.nanoTime();
        SingleSourcePolicyEngine.PolicyEvaluationInput policyInput = buildPolicyInput(complaintType, snapshot);
        SingleSourcePolicyEngine.AdjudicationResult adjudication = policyEngine.evaluate(
                bundle, policyHash, policyInput);
        spans.add(span("policy", "single_source_policy_engine", elapsedMs(policyStart),
                Map.of("status", adjudication.status().name(), "policy_version", bundle.policyVersion())));

        long extractStart = System.nanoTime();
        UnstructuredExtractionPort.ExtractionOutcome extraction = UnstructuredExtractionPort.ExtractionOutcome.empty();
        List<String> degradedReasons = new ArrayList<>();
        if (!snapshot.messages().isEmpty()) {
            try {
                extraction = extractionPort.extract(snapshot);
                if (extraction.degraded() && extraction.degradedReason() != null) {
                    degradedReasons.add(extraction.degradedReason());
                }
            } catch (RuntimeException e) {
                log.warn("Optional extraction failed for caseId={}: {}", caseId, e.getMessage());
                degradedReasons.add("extraction_failed:" + e.getClass().getSimpleName());
                extraction = new UnstructuredExtractionPort.ExtractionOutcome(
                        true, "extraction_failed", List.of(), false, true);
            }
        }
        spans.add(span("extraction", "unstructured_extractor", elapsedMs(extractStart),
                Map.of("signals", String.valueOf(extraction.unconfirmedSignals().size()),
                        "untrusted", String.valueOf(extraction.untrustedTextDetected()))));

        boolean hasConflictSignal = !extraction.unconfirmedSignals().isEmpty();
        RoutingAction routing = policyEngine.route(
                bundle, adjudication, hasConflictSignal,
                extraction.verificationFailed(), extraction.untrustedTextDetected());

        CustomerDraft draft = templateRenderer.render(adjudication, false, null);
        RunHealth health = degradedReasons.isEmpty() ? RunHealth.OK : RunHealth.DEGRADED;

        long totalMs = elapsedMs(startNanos);
        AssessmentRun run = new AssessmentRun(
                "RUN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                traceId,
                runNumber,
                clock.instant(),
                snapshot.snapshotId(),
                snapshot.contentHash(),
                bundle.policyVersion(),
                policyHash,
                health,
                List.copyOf(degradedReasons),
                diagnosis,
                adjudication,
                routing,
                draft,
                extraction.unconfirmedSignals(),
                extraction.untrustedTextDetected(),
                extraction.verificationFailed());

        recordTrace(traceId, caseId, complaintType, snapshot, run, spans, totalMs);
        metricsService.incrementCounter("assessment_run_" + health.name().toLowerCase());
        metricsService.recordLatency("assessment_run_latency", totalMs);
        return run;
    }

    @SuppressWarnings("unchecked")
    private static SingleSourcePolicyEngine.PolicyEvaluationInput buildPolicyInput(
            ComplaintType complaintType, EvidenceSnapshot snapshot) {
        Map<EvidenceType, EvidenceValue<?>> latest = snapshot.latestValuesByType();
        EvidenceValue<String> orderStatus = castEvidence(latest.get(EvidenceType.ORDER_STATUS));
        EvidenceValue<Long> overtime = castEvidence(latest.get(EvidenceType.TIMELINE_OVERTIME_MINUTES));
        EvidenceValue<BigDecimal> orderAmount = castEvidence(latest.get(EvidenceType.ORDER_AMOUNT));
        EvidenceValue<DamageLevel> damageLevel = castEvidence(latest.get(EvidenceType.DAMAGE_LEVEL));

        Instant evaluationAt = snapshot.latestByType(EvidenceType.ORDER_DELIVERY_TIME)
                .map(EvidenceItem::value)
                .flatMap(EvidenceValue::knownValue)
                .filter(Instant.class::isInstance)
                .map(Instant.class::cast)
                .orElse(null);

        return new SingleSourcePolicyEngine.PolicyEvaluationInput(
                orderStatus, complaintType, overtime, orderAmount, damageLevel, evaluationAt, latest);
    }

    @SuppressWarnings("unchecked")
    private static <T> EvidenceValue<T> castEvidence(EvidenceValue<?> raw) {
        if (raw == null) {
            return EvidenceValue.unknown("MISSING_EVIDENCE", "not present in snapshot");
        }
        return (EvidenceValue<T>) raw;
    }

    private static CaseStatus initialStatusFor(AssessmentRun run) {
        if (run.runHealth() == RunHealth.FAILED) {
            return CaseStatus.ESCALATED;
        }
        return switch (run.routingAction()) {
            case WAITING_EVIDENCE -> CaseStatus.WAITING_EVIDENCE;
            case ESCALATED -> CaseStatus.ESCALATED;
            case PENDING_DECISION -> CaseStatus.PENDING_DECISION;
        };
    }

    private static BigDecimal resolveAmountToApprove(
            DeliveryCase current, AssessmentRun latestRun, BigDecimal commandAmount) {
        if (commandAmount != null) {
            return commandAmount;
        }
        if (current.pendingOverrideAmount() != null) {
            return current.pendingOverrideAmount();
        }
        if (latestRun.adjudication().actionableAmount() != null) {
            return latestRun.adjudication().actionableAmount();
        }
        return latestRun.adjudication().calculatedAmount();
    }

    private static void verifyCaseVersion(DeliveryCase current, Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new BizException(400, "expectedCaseVersion is required");
        }
        if (expectedVersion != current.version()) {
            throw new ConflictException("Case version conflict: expected " + expectedVersion
                    + ", current is " + current.version());
        }
    }

    private static void requireStatus(DeliveryCase current, CaseStatus... allowed) {
        for (CaseStatus status : allowed) {
            if (current.status() == status) {
                return;
            }
        }
        throw new ConflictException("Action not allowed when case status is " + current.status());
    }

    private static AttestableEvidenceType parseAttestableType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(400, "evidenceType is required");
        }
        try {
            return AttestableEvidenceType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "Unsupported attestable evidenceType: " + raw);
        }
    }

    private static Object parseAttestableValue(AttestableEvidenceType type, String rawValue) {
        if (type == AttestableEvidenceType.DAMAGE_LEVEL) {
            return DamageLevel.parse(rawValue)
                    .orElseThrow(() -> new BizException(400, "DAMAGE_LEVEL must be FULL or PARTIAL"));
        }
        throw new BizException(400, "Unsupported attestable type: " + type);
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BizException(400, message);
        }
        return value.trim();
    }

    private static String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String ensureTraceId() {
        String existing = TraceUtil.getTraceId();
        return existing != null ? existing : TraceUtil.generateTraceId();
    }

    private void recordTrace(
            String traceId,
            String caseId,
            ComplaintType complaintType,
            EvidenceSnapshot snapshot,
            AssessmentRun run,
            List<TraceService.TraceSpan> spans,
            long totalMs) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("case_id", caseId);
        input.put("order_id", snapshot.orderId());
        input.put("complaint_type", complaintType.name());
        input.put("snapshot_hash", snapshot.contentHash());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("run_id", run.runId());
        output.put("run_health", run.runHealth().name());
        output.put("adjudication_status", run.adjudication().status().name());
        output.put("routing_action", run.routingAction().name());
        output.put("policy_version", run.policyVersion());
        output.put("policy_hash", run.policyHash());

        traceService.recordTrace(TraceService.TraceRecord.builder()
                .traceId(traceId)
                .scenario("case_assessment")
                .input(input)
                .output(output)
                .spans(spans)
                .totalDurationMs(totalMs)
                .status(run.runHealth().name())
                .createdAt(LocalDateTime.now(clock))
                .build());
    }

    private static TraceService.TraceSpan span(
            String type, String name, long durationMs, Map<String, String> attrs) {
        return TraceService.TraceSpan.builder()
                .spanId(UUID.randomUUID().toString())
                .spanType(type)
                .name(name)
                .durationMs(durationMs)
                .attributes(new LinkedHashMap<>(attrs))
                .build();
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    public record CreateCaseCommand(String orderId, String complaintType, String idempotencyKey) {}

    public record CreateCaseResult(DeliveryCase deliveryCase, boolean idempotentReplay) {}

    public record AttestCommand(
            String evidenceType,
            String value,
            String operatorId,
            Integer expectedCaseVersion) {}

    public record DecisionCommand(
            String runId,
            Integer expectedCaseVersion,
            DecisionAction action,
            String operatorId,
            String overrideReasonCode,
            BigDecimal finalAmount,
            String finalMethod,
            String finalLiability,
            String exceptionReason,
            String externalReceiptId,
            String note) {}

    public record PolicySummaryView(
            PolicyBundle bundle,
            String policyHash,
            List<Map<String, Object>> renderedRules) {}
}

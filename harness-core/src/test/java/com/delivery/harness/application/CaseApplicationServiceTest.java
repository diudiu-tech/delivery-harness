package com.delivery.harness.application;

import com.delivery.harness.cases.CaseStatus;
import com.delivery.harness.cases.DecisionAction;
import com.delivery.harness.cases.DeliveryCase;
import com.delivery.harness.cases.InMemoryCaseStore;
import com.delivery.harness.cases.RunHealth;
import com.delivery.harness.common.exception.BizException;
import com.delivery.harness.common.exception.ConflictException;
import com.delivery.harness.common.exception.ResourceNotFoundException;
import com.delivery.harness.diagnosis.DeterministicResidualEngine;
import com.delivery.harness.evidence.SyntheticEvidenceSource;
import com.delivery.harness.observe.metrics.MetricsService;
import com.delivery.harness.observe.trace.TraceService;
import com.delivery.harness.policy.AdjudicationStatus;
import com.delivery.harness.policy.PolicyBundleLoader;
import com.delivery.harness.policy.SingleSourcePolicyEngine;
import com.delivery.harness.template.DeterministicCustomerTemplateRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseApplicationServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-01T04:05:00Z"), ZoneId.of("Asia/Shanghai"));

    private CaseApplicationService service;
    private TraceService traceService;

    @BeforeEach
    void setUp() {
        PolicyBundleLoader loader = PolicyBundleLoader.loadDefault();
        SyntheticEvidenceSource evidenceSource = new SyntheticEvidenceSource(loader);
        InMemoryCaseStore store = new InMemoryCaseStore(50);
        SingleSourcePolicyEngine policyEngine = new SingleSourcePolicyEngine(loader);
        DeterministicResidualEngine residualEngine = new DeterministicResidualEngine(loader);
        DeterministicCustomerTemplateRenderer templateRenderer = new DeterministicCustomerTemplateRenderer();
        traceService = new TraceService(50);
        MetricsService metricsService = new MetricsService();
        service = new CaseApplicationService(
                evidenceSource, store, loader, policyEngine, residualEngine,
                templateRenderer, traceService, metricsService, FIXED_CLOCK, null);
    }

    @Test
    void enforcesIdempotencyKeyAndSingleOpenCaseRule() {
        CaseApplicationService.CreateCaseResult first = service.createCase(
                new CaseApplicationService.CreateCaseCommand("ORD-MERCHANT-01", "OVERTIME", "idem-1"));
        CaseApplicationService.CreateCaseResult replay = service.createCase(
                new CaseApplicationService.CreateCaseCommand("ORD-MERCHANT-01", "OVERTIME", "idem-1"));

        assertAll(
                () -> assertFalse(first.idempotentReplay()),
                () -> assertTrue(replay.idempotentReplay()),
                () -> assertEquals(first.deliveryCase().caseId(), replay.deliveryCase().caseId()),
                () -> assertEquals(CaseStatus.PENDING_DECISION, first.deliveryCase().status()),
                () -> assertEquals(RunHealth.OK, first.deliveryCase().latestRun().runHealth()),
                () -> assertTrue(traceService.getTrace(first.deliveryCase().latestRun().traceId()).isPresent()));

        // Same Idempotency-Key with different order or complaintType must fail with 409 Conflict.
        assertThrows(ConflictException.class, () -> service.createCase(
                new CaseApplicationService.CreateCaseCommand("ORD-SEVERE-01", "OVERTIME", "idem-1")));
        assertThrows(ConflictException.class, () -> service.createCase(
                new CaseApplicationService.CreateCaseCommand("ORD-MERCHANT-01", "DAMAGED", "idem-1")));

        assertThrows(ConflictException.class, () -> service.createCase(
                new CaseApplicationService.CreateCaseCommand("ORD-MERCHANT-01", "OVERTIME", "idem-2")));
    }

    @Test
    void throwsNotFoundForUnknownOrder() {
        assertThrows(ResourceNotFoundException.class, () -> service.createCase(
                new CaseApplicationService.CreateCaseCommand("ORD-NOPE", "OVERTIME", null)));
    }

    @Test
    void completesAttestApproveReceiptAndReopenLifecycle() {
        DeliveryCase created = service.createCase(
                new CaseApplicationService.CreateCaseCommand("ORD-IM-DAMAGE-01", "DAMAGED", null))
                .deliveryCase();
        String run1Id = created.latestRun().runId();

        assertAll(
                () -> assertEquals(CaseStatus.WAITING_EVIDENCE, created.status()),
                () -> assertEquals(AdjudicationStatus.NEEDS_EVIDENCE, created.latestRun().adjudication().status()),
                () -> assertNull(created.latestRun().adjudication().actionableAmount()),
                () -> assertFalse(created.latestCustomerDraft().executionCompleted()));

        // Attest DAMAGE_LEVEL=PARTIAL -> triggers Run #2 -> ELIGIBLE 20.00 -> ESCALATED.
        DeliveryCase attested = service.attestEvidence(
                created.caseId(),
                new CaseApplicationService.AttestCommand("DAMAGE_LEVEL", "PARTIAL", "agent-1", created.version()));
        String run2Id = attested.latestRun().runId();

        assertAll(
                () -> assertEquals(2, attested.runs().size()),
                () -> assertNotEquals(run1Id, run2Id),
                () -> assertEquals(CaseStatus.ESCALATED, attested.status()),
                () -> assertEquals(AdjudicationStatus.ELIGIBLE, attested.latestRun().adjudication().status()),
                () -> assertEquals(new BigDecimal("20.00"), attested.latestRun().adjudication().actionableAmount()),
                () -> assertTrue(attested.latestSnapshot().containsEvidenceId("EV-HUMAN-01")));

        // Stale runId must fail with 409 Conflict.
        assertThrows(ConflictException.class, () -> service.submitDecision(
                attested.caseId(),
                new CaseApplicationService.DecisionCommand(
                        run1Id, attested.version(), DecisionAction.APPROVE, "sup-1",
                        null, null, null, null, null, null, null)));

        // Supervisor approves -> APPROVED_PENDING_EXECUTION.
        DeliveryCase approved = service.submitDecision(
                attested.caseId(),
                new CaseApplicationService.DecisionCommand(
                        run2Id, attested.version(), DecisionAction.APPROVE, "sup-1",
                        null, null, null, "MERCHANT", null, null, "核验照片确认部分洒漏"));
        assertEquals(CaseStatus.APPROVED_PENDING_EXECUTION, approved.status());
        assertFalse(approved.latestCustomerDraft().executionCompleted());

        // Record external execution receipt -> CLOSED + unlocks executed template.
        DeliveryCase closed = service.submitDecision(
                approved.caseId(),
                new CaseApplicationService.DecisionCommand(
                        run2Id, approved.version(), DecisionAction.RECORD_EXECUTION_RECEIPT, "ops-1",
                        null, null, null, null, null, "EXT-RCPT-9001", null));
        assertAll(
                () -> assertEquals(CaseStatus.CLOSED, closed.status()),
                () -> assertEquals("EXT-RCPT-9001", closed.executionReceiptId()),
                () -> assertTrue(closed.latestCustomerDraft().executionCompleted()),
                () -> assertTrue(closed.latestCustomerDraft().message().contains("已完成赔付处理")));

        // Reopen from CLOSED immediately executes Run #3 and never stalls in OPEN_INVESTIGATING.
        DeliveryCase reopened = service.submitDecision(
                closed.caseId(),
                new CaseApplicationService.DecisionCommand(
                        run2Id, closed.version(), DecisionAction.REOPEN, "sup-1",
                        null, null, null, null, null, null, "用户补充申诉"));
        assertAll(
                () -> assertEquals(3, reopened.runs().size()),
                () -> assertNotEquals(CaseStatus.OPEN_INVESTIGATING, reopened.status()),
                () -> assertNull(reopened.executionReceiptId()),
                () -> assertFalse(reopened.latestCustomerDraft().executionCompleted()),
                () -> assertTrue(reopened.latestSnapshot().containsEvidenceId("EV-HUMAN-01"),
                        "REOPEN must preserve previously attested EV-HUMAN-* facts"),
                () -> assertEquals(AdjudicationStatus.ELIGIBLE, reopened.latestRun().adjudication().status()));
    }

    @Test
    void enforcesEscalationOnOverCapOverrideAndRequiresExceptionReasonOnApprove() {
        DeliveryCase created = service.createCase(
                new CaseApplicationService.CreateCaseCommand("ORD-MERCHANT-01", "OVERTIME", null))
                .deliveryCase();

        // Override amount 60.00 > escalationCap (50.00) must force ESCALATED.
        DeliveryCase escalated = service.submitDecision(
                created.caseId(),
                new CaseApplicationService.DecisionCommand(
                        created.latestRun().runId(), created.version(), DecisionAction.OVERRIDE, "agent-1",
                        "VIP_EXCEPTION", new BigDecimal("60.00"), "原路退款", "PLATFORM", null, null, "特殊客诉"));
        assertEquals(CaseStatus.ESCALATED, escalated.status());

        // Supervisor approving > escalationCap without exceptionReason must fail with 400.
        assertThrows(BizException.class, () -> service.submitDecision(
                escalated.caseId(),
                new CaseApplicationService.DecisionCommand(
                        escalated.latestRun().runId(), escalated.version(), DecisionAction.APPROVE, "sup-1",
                        null, null, null, null, null, null, null)));

        DeliveryCase approved = service.submitDecision(
                escalated.caseId(),
                new CaseApplicationService.DecisionCommand(
                        escalated.latestRun().runId(), escalated.version(), DecisionAction.APPROVE, "sup-1",
                        null, null, null, null, "主管特批大额客诉", null, null));
        assertEquals(CaseStatus.APPROVED_PENDING_EXECUTION, approved.status());
        assertEquals(new BigDecimal("60.00"), approved.approvedPayoutAmount());

        // Recording receipt after override/over-cap approval must render the actual approved amount (60.00), not rule 5.00.
        DeliveryCase executed = service.submitDecision(
                approved.caseId(),
                new CaseApplicationService.DecisionCommand(
                        approved.latestRun().runId(), approved.version(), DecisionAction.RECORD_EXECUTION_RECEIPT, "ops-1",
                        null, null, null, null, null, "PAY-OVER-60", null));
        assertAll(
                () -> assertEquals(CaseStatus.CLOSED, executed.status()),
                () -> assertTrue(executed.latestCustomerDraft().executionCompleted()),
                () -> assertTrue(executed.latestCustomerDraft().message().contains("60.00 元")),
                () -> assertTrue(executed.latestCustomerDraft().message().contains("原路退款")),
                () -> assertFalse(executed.latestCustomerDraft().message().contains("已按平台规则")
                        && executed.latestCustomerDraft().message().contains("已完成赔付处理")));
    }

    @Test
    void enforcesApproveAmountBoundsAndAtomicConcurrentUpdates() throws Exception {
        // 1. Escalate a WAITING_EVIDENCE case (no determinable amount) -> APPROVE without final_amount must 400.
        DeliveryCase missing = service.createCase(
                new CaseApplicationService.CreateCaseCommand("ORD-MISSING-TS-01", "OVERTIME", null))
                .deliveryCase();
        DeliveryCase escalatedMissing = service.submitDecision(
                missing.caseId(),
                new CaseApplicationService.DecisionCommand(
                        missing.latestRun().runId(), missing.version(), DecisionAction.ESCALATE, "agent-1",
                        null, null, null, null, null, null, "时间缺失升级核查"));
        assertThrows(BizException.class, () -> service.submitDecision(
                escalatedMissing.caseId(),
                new CaseApplicationService.DecisionCommand(
                        escalatedMissing.latestRun().runId(), escalatedMissing.version(),
                        DecisionAction.APPROVE, "sup-1", null, null, null, null, null, null, null)));
        // Negative finalAmount on APPROVE must also fail with 400.
        assertThrows(BizException.class, () -> service.submitDecision(
                escalatedMissing.caseId(),
                new CaseApplicationService.DecisionCommand(
                        escalatedMissing.latestRun().runId(), escalatedMissing.version(),
                        DecisionAction.APPROVE, "sup-1", null, new BigDecimal("-5.00"), null, null, null, null, null)));
        // Approving explicit 0.00 closes directly without requiring fake execution receipt.
        DeliveryCase approvedZero = service.submitDecision(
                escalatedMissing.caseId(),
                new CaseApplicationService.DecisionCommand(
                        escalatedMissing.latestRun().runId(), escalatedMissing.version(),
                        DecisionAction.APPROVE, "sup-1", null, BigDecimal.ZERO, null, null, null, null, "核实无需赔付"));
        assertEquals(CaseStatus.CLOSED, approvedZero.status());

        // 2. Concurrent decisions with identical expected_case_version: exactly 1 succeeds, 1 throws ConflictException.
        DeliveryCase target = service.createCase(
                new CaseApplicationService.CreateCaseCommand("ORD-ROAD-01", "OVERTIME", null))
                .deliveryCase();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final String op = "reviewer-" + i;
                tasks.add(() -> {
                    startGate.await();
                    service.submitDecision(
                            target.caseId(),
                            new CaseApplicationService.DecisionCommand(
                                    target.latestRun().runId(), target.version(), DecisionAction.ACCEPT, op,
                                    null, null, null, null, null, null, null));
                    return true;
                });
            }
            List<Future<Boolean>> futures = List.of(pool.submit(tasks.get(0)), pool.submit(tasks.get(1)));
            startGate.countDown();
            int successes = 0;
            int conflicts = 0;
            for (Future<Boolean> f : futures) {
                try {
                    if (f.get()) {
                        successes++;
                    }
                } catch (java.util.concurrent.ExecutionException ex) {
                    if (ex.getCause() instanceof ConflictException) {
                        conflicts++;
                    } else {
                        throw ex;
                    }
                }
            }
            assertEquals(1, successes, "Exactly one concurrent decision should win CAS");
            assertEquals(1, conflicts, "The losing concurrent decision must receive 409 ConflictException");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void supportsRequestEvidenceAndCloseEscapeHatch() {
        DeliveryCase created = service.createCase(
                new CaseApplicationService.CreateCaseCommand("ORD-MERCHANT-01", "OVERTIME", null))
                .deliveryCase();

        DeliveryCase waiting = service.submitDecision(
                created.caseId(),
                new CaseApplicationService.DecisionCommand(
                        created.latestRun().runId(), created.version(), DecisionAction.REQUEST_EVIDENCE, "agent-1",
                        null, null, null, null, null, null, "需补充签收凭证"));
        assertEquals(CaseStatus.WAITING_EVIDENCE, waiting.status());

        DeliveryCase closed = service.submitDecision(
                waiting.caseId(),
                new CaseApplicationService.DecisionCommand(
                        waiting.latestRun().runId(), waiting.version(), DecisionAction.CLOSE, "agent-1",
                        null, null, null, null, null, null, "用户 48 小时未补证关闭"));
        assertEquals(CaseStatus.CLOSED, closed.status());
    }
}

package com.delivery.harness.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.auth.PrincipalDirectory;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

class CaseServiceTest {
    private PlatformStore store;
    private CaseService cases;
    private final Principal agent =
            new Principal("t", "agent", Set.of("AGENT"), Set.of("*"), Set.of("*"));
    private final Principal reviewer =
            new Principal("t", "reviewer", Set.of("REVIEWER"), Set.of("*"), Set.of("*"));

    @BeforeEach
    void setup() {
        store = PlatformTestDatabase.memory();
        cases = new CaseService(store, new OrderSource("demo", "", "", ""));
        PrincipalDirectory directory = new PrincipalDirectory("pilot");
        directory.registerAuthenticated(
                new Principal("t", "reviewer-2", Set.of("REVIEWER"), Set.of("*"), Set.of("*")));
        ReflectionTestUtils.setField(cases, "directory", directory);
    }

    @Test
    void approvalBindsImmutableDecisionAndNeverPays() {
        var c =
                cases.create(
                        agent,
                        Map.of("order_id", "ORD-MERCHANT-SLOW", "complaint_type", "OVERTIME"));
        var review = prepareDecision(c.id());
        var claimed = cases.claim(reviewer, review.id(), review.version());
        var current = cases.require(reviewer, c.id());
        var action =
                cases.act(
                        reviewer,
                        review.id(),
                        Map.of(
                                "expected_version",
                                claimed.version(),
                                "action",
                                "APPROVE",
                                "reason",
                                "confirmed evidence",
                                "decision_id",
                                "decision-1",
                                "decision_version",
                                1,
                                "evidence_snapshot_id",
                                current.data().get("evidence_snapshot_id")));
        assertEquals("NOT_EXECUTED", action.data().get("payment_status"));
        assertEquals("APPROVED", cases.require(reviewer, c.id()).data().get("status"));
        assertEquals("NOT_EXECUTED", cases.require(reviewer, c.id()).data().get("payment_status"));
    }

    @Test
    void addedEvidenceInvalidatesApprovalAndOldReviewCannotSubmit() {
        var c =
                cases.create(
                        agent,
                        Map.of("order_id", "ORD-MERCHANT-SLOW", "complaint_type", "OVERTIME"));
        var review = prepareDecision(c.id());
        var claimed = cases.claim(reviewer, review.id(), 1);
        String previousSnapshot =
                cases.require(reviewer, c.id()).data().get("evidence_snapshot_id").toString();
        cases.addEvidence(
                agent,
                c.id(),
                Map.of(
                        "file_hash",
                        "a".repeat(64),
                        "mime_type",
                        "image/png",
                        "size_bytes",
                        20,
                        "reference_uri",
                        "https://evidence.example/item"));
        assertNotEquals(
                previousSnapshot,
                cases.require(reviewer, c.id()).data().get("evidence_snapshot_id"));
        assertEquals("SUPERSEDED", store.require("t", "review", review.id()).data().get("status"));
        assertThrows(
                PlatformException.class,
                () ->
                        cases.act(
                                reviewer,
                                review.id(),
                                Map.of(
                                        "expected_version",
                                        claimed.version(),
                                        "action",
                                        "APPROVE",
                                        "reason",
                                        "stale",
                                        "decision_id",
                                        "decision-1",
                                        "decision_version",
                                        1,
                                        "evidence_snapshot_id",
                                        previousSnapshot)));
    }

    @Test
    void damageMaterialNeedsIndependentVerification() {
        var c = cases.create(agent, Map.of("order_id", "ORD-DAMAGE", "complaint_type", "DAMAGED"));
        var m = cases.addEvidence(agent, c.id(), inlineMaterial("FULL"));
        assertFalse(cases.verifiedDamage(agent, c.id(), "FULL"));
        assertThrows(
                PlatformException.class,
                () ->
                        cases.reviewMaterial(
                                new Principal(
                                        "t", "agent", Set.of("REVIEWER"), Set.of("*"), Set.of("*")),
                                m.id(),
                                Map.of(
                                        "expected_version",
                                        1,
                                        "action",
                                        "VERIFY",
                                        "reason",
                                        "self")));
        cases.reviewMaterial(
                reviewer,
                m.id(),
                Map.of(
                        "expected_version",
                        1,
                        "action",
                        "VERIFY",
                        "reason",
                        "manually inspected original"));
        assertTrue(cases.verifiedDamage(agent, c.id(), "FULL"));
    }

    @Test
    void approvalAuditStorageFailureRollsBackAndCanBeRetried() throws Exception {
        var c =
                cases.create(
                        agent, Map.of("order_id", "ORDER-AUDIT", "complaint_type", "OVERTIME"));
        var review = prepareDecision(c.id());
        var claimed = cases.claim(reviewer, review.id(), 1);
        var current = cases.require(reviewer, c.id());
        Map<String, Object> action =
                Map.of(
                        "expected_version",
                        claimed.version(),
                        "action",
                        "APPROVE",
                        "reason",
                        "confirmed",
                        "decision_id",
                        "decision-1",
                        "decision_version",
                        1,
                        "evidence_snapshot_id",
                        current.data().get("evidence_snapshot_id"));
        var source = (DataSource) ReflectionTestUtils.getField(store, "source");
        try (var connection = source.getConnection();
                var command = connection.createStatement()) {
            command.execute("ALTER TABLE platform_audit_events RENAME TO platform_audit_backup");
        }
        assertEquals(
                503,
                assertThrows(
                                PlatformException.class,
                                () -> cases.act(reviewer, review.id(), action))
                        .status());
        assertEquals("IN_REVIEW", store.require("t", "review", review.id()).data().get("status"));
        assertEquals("IN_REVIEW", cases.require(reviewer, c.id()).data().get("status"));
        assertTrue(store.list("t", "review_action").isEmpty());
        try (var connection = source.getConnection();
                var command = connection.createStatement()) {
            command.execute("ALTER TABLE platform_audit_backup RENAME TO platform_audit_events");
        }
        cases.act(reviewer, review.id(), action);
        assertEquals("APPROVED", cases.require(reviewer, c.id()).data().get("status"));
        assertFalse(store.audits("t").isEmpty());
    }

    @Test
    void externalUrlsCannotBecomeVerifiedDamageWithoutFrozenContent() {
        var c = cases.create(agent, Map.of("order_id", "ORD-DAMAGE", "complaint_type", "DAMAGED"));
        var m =
                cases.addEvidence(
                        agent,
                        c.id(),
                        Map.of(
                                "file_hash",
                                "b".repeat(64),
                                "mime_type",
                                "image/jpeg",
                                "size_bytes",
                                20,
                                "reference_uri",
                                "https://evidence.example/item",
                                "damage_level",
                                "FULL"));
        assertThrows(
                PlatformException.class,
                () ->
                        cases.reviewMaterial(
                                reviewer,
                                m.id(),
                                Map.of(
                                        "expected_version",
                                        1,
                                        "action",
                                        "VERIFY",
                                        "reason",
                                        "clicked checkbox")));
        assertFalse(cases.verifiedDamage(agent, c.id(), "FULL"));
    }

    @Test
    void nonexistentOrWrongScopeReassignmentCannotOrphanTheReview() {
        var c =
                cases.create(
                        agent, Map.of("order_id", "ORDER-OWNER", "complaint_type", "OVERTIME"));
        var review = prepareDecision(c.id());
        var claimed = cases.claim(reviewer, review.id(), 1);
        var current = cases.require(reviewer, c.id());
        Map<String, Object> action =
                new LinkedHashMap<>(
                        Map.of(
                                "expected_version",
                                claimed.version(),
                                "action",
                                "REASSIGN",
                                "reason",
                                "handover",
                                "decision_id",
                                "decision-1",
                                "decision_version",
                                1,
                                "evidence_snapshot_id",
                                current.data().get("evidence_snapshot_id"),
                                "target_user_id",
                                "nonexistent"));
        assertEquals(
                "REVIEWER_NOT_FOUND",
                assertThrows(
                                PlatformException.class,
                                () -> cases.act(reviewer, review.id(), action))
                        .code());
        assertEquals("reviewer", store.require("t", "review", review.id()).data().get("owner"));
        assertEquals(claimed.version(), store.require("t", "review", review.id()).version());
    }

    @Test
    void fundsRoleCanReadScopedCasesButCannotModifyEvidence() {
        var c =
                cases.create(
                        agent, Map.of("order_id", "ORDER-FUNDS", "complaint_type", "OVERTIME"));
        var funds = new Principal("t", "finance", Set.of("FUNDS"), Set.of("*"), Set.of("*"));
        assertEquals(c.id(), cases.require(funds, c.id()).id());
        assertThrows(
                PlatformException.class,
                () -> cases.addEvidence(funds, c.id(), inlineMaterial("FULL")));
    }

    @Test
    void claimAndReassignmentSynchronizeCaseOwner() {
        var c =
                cases.create(
                        agent, Map.of("order_id", "ORDER-OWNER", "complaint_type", "OVERTIME"));
        var review = prepareDecision(c.id());
        var claimed = cases.claim(reviewer, review.id(), 1);
        assertEquals("reviewer", cases.require(reviewer, c.id()).data().get("assignee"));
        var current = cases.require(reviewer, c.id());
        cases.act(
                reviewer,
                review.id(),
                Map.of(
                        "expected_version",
                        claimed.version(),
                        "action",
                        "REASSIGN",
                        "reason",
                        "shift handover",
                        "decision_id",
                        "decision-1",
                        "decision_version",
                        1,
                        "evidence_snapshot_id",
                        current.data().get("evidence_snapshot_id"),
                        "target_user_id",
                        "reviewer-2"));
        assertEquals("reviewer-2", cases.require(reviewer, c.id()).data().get("assignee"));
    }

    @Test
    void activeOrCompletedPaymentsLockEvidenceAndClosure() {
        for (String status : List.of("PENDING", "SUBMITTED", "UNKNOWN", "SUCCEEDED")) {
            setup();
            var c =
                    cases.create(
                            agent, Map.of("order_id", "ORDER-FUNDS", "complaint_type", "DAMAGED"));
            var material = cases.addEvidence(agent, c.id(), inlineMaterial("FULL"));
            var latest = cases.require(agent, c.id());
            Map<String, Object> data = new LinkedHashMap<>(latest.data());
            data.put("payment_status", status);
            data.put("status", "APPROVED");
            store.update("t", "case", c.id(), latest.version(), data, "funds", "PAYMENT");
            assertThrows(
                    PlatformException.class,
                    () -> cases.addEvidence(agent, c.id(), inlineMaterial("FULL")));
            assertThrows(
                    PlatformException.class,
                    () ->
                            cases.reviewMaterial(
                                    reviewer,
                                    material.id(),
                                    Map.of(
                                            "expected_version",
                                            1,
                                            "action",
                                            "VERIFY",
                                            "reason",
                                            "inspected")));
            assertThrows(
                    PlatformException.class,
                    () ->
                            cases.close(
                                    agent,
                                    c.id(),
                                    Map.of(
                                            "expected_version",
                                            cases.require(agent, c.id()).version(),
                                            "reason",
                                            "close")));
            assertEquals(status, cases.require(agent, c.id()).data().get("payment_status"));
            assertEquals(1, store.list("t", "material").size());
        }
    }

    public static Map<String, Object> inlineMaterial(String level) {
        byte[] content = "manually inspected damage evidence".getBytes(StandardCharsets.UTF_8);
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return Map.of(
                "file_hash",
                hash,
                "mime_type",
                "text/plain",
                "size_bytes",
                content.length,
                "content_base64",
                Base64.getEncoder().encodeToString(content),
                "damage_level",
                level);
    }

    @Test
    void scopesAndTenantAreServerEnforced() {
        var c = cases.create(agent, Map.of("order_id", "ORD-DAMAGE", "complaint_type", "DAMAGED"));
        assertThrows(
                PlatformException.class,
                () ->
                        cases.require(
                                new Principal(
                                        "different",
                                        "other",
                                        Set.of("AGENT"),
                                        Set.of("*"),
                                        Set.of("*")),
                                c.id()));
        assertThrows(
                PlatformException.class,
                () ->
                        cases.require(
                                new Principal(
                                        "t",
                                        "scoped",
                                        Set.of("AGENT"),
                                        Set.of("denied-city"),
                                        Set.of("*")),
                                c.id()));
    }

    @Test
    void caseInitiatorCannotClaimTheirOwnOrdinaryDecisionReview() {
        var c = cases.create(agent, Map.of("order_id", "SELF-CASE", "complaint_type", "OVERTIME"));
        var review = prepareDecision(c.id(), "another-initiator");
        var self =
                new Principal(
                        "t",
                        agent.userId(),
                        Set.of("AGENT", "REVIEWER", "SUPERVISOR"),
                        Set.of("*"),
                        Set.of("*"));
        var failure =
                assertThrows(
                        PlatformException.class,
                        () -> cases.claim(self, review.id(), review.version()));
        assertEquals("SEPARATION_OF_DUTIES", failure.code());
        assertEquals("PENDING", store.require("t", "review", review.id()).data().get("status"));
    }

    @Test
    void ordinaryDecisionInitiatorCannotClaimAndBypassClaimToApprove() {
        var c =
                cases.create(
                        agent, Map.of("order_id", "SELF-DECISION", "complaint_type", "OVERTIME"));
        var review = prepareDecision(c.id(), reviewer.userId());
        assertEquals(
                "SEPARATION_OF_DUTIES",
                assertThrows(
                                PlatformException.class,
                                () -> cases.claim(reviewer, review.id(), review.version()))
                        .code());
        Map<String, Object> assigned = new LinkedHashMap<>(review.data());
        assigned.put("status", "IN_REVIEW");
        assigned.put("owner", reviewer.userId());
        var claimed =
                store.update(
                        "t",
                        "review",
                        review.id(),
                        review.version(),
                        assigned,
                        "legacy",
                        "LEGACY_ASSIGNMENT");
        var current = store.require("t", "case", c.id());
        Map<String, Object> action =
                Map.of(
                        "expected_version",
                        claimed.version(),
                        "action",
                        "APPROVE",
                        "reason",
                        "legacy owner",
                        "decision_id",
                        "decision-1",
                        "decision_version",
                        1,
                        "evidence_snapshot_id",
                        current.data().get("evidence_snapshot_id"));
        assertEquals(
                "SEPARATION_OF_DUTIES",
                assertThrows(
                                PlatformException.class,
                                () -> cases.act(reviewer, review.id(), action))
                        .code());
        assertEquals("IN_REVIEW", store.require("t", "review", review.id()).data().get("status"));
        assertTrue(store.caseRecords("t", "review_action", c.id(), 100).isEmpty());
    }

    @Test
    void legacyDecisionWithoutVerifiedInitiatorCannotBeClaimedOrApproved() {
        var c =
                cases.create(
                        agent, Map.of("order_id", "LEGACY-AUTHOR", "complaint_type", "OVERTIME"));
        var review = prepareDecision(c.id(), null);
        assertEquals(
                "AUTHORSHIP_UNVERIFIED",
                assertThrows(
                                PlatformException.class,
                                () -> cases.claim(reviewer, review.id(), review.version()))
                        .code());
        Map<String, Object> assigned = new LinkedHashMap<>(review.data());
        assigned.put("status", "IN_REVIEW");
        assigned.put("owner", reviewer.userId());
        var claimed =
                store.update(
                        "t",
                        "review",
                        review.id(),
                        review.version(),
                        assigned,
                        "legacy",
                        "LEGACY_ASSIGNMENT");
        Map<String, Object> action =
                Map.of(
                        "expected_version",
                        claimed.version(),
                        "action",
                        "APPROVE",
                        "reason",
                        "Legacy owner attempted approval",
                        "decision_id",
                        "decision-1",
                        "decision_version",
                        1,
                        "evidence_snapshot_id",
                        c.data().get("evidence_snapshot_id"));
        assertEquals(
                "AUTHORSHIP_UNVERIFIED",
                assertThrows(
                                PlatformException.class,
                                () -> cases.act(reviewer, review.id(), action))
                        .code());
        assertTrue(store.caseRecords("t", "review_action", c.id(), 100).isEmpty());
    }

    @Test
    void frozenAttachmentBodyIsNotRepeatedInMetadataQueries() {
        var c = cases.create(agent, Map.of("order_id", "BODY-CASE", "complaint_type", "DAMAGED"));
        var material = cases.addEvidence(agent, c.id(), inlineMaterial("FULL"));
        assertFalse(material.data().containsKey("content_base64"));
        assertFalse(
                store.caseRecords("t", "material", c.id(), 100)
                        .get(0)
                        .data()
                        .containsKey("content_base64"));
        assertArrayEquals(
                Base64.getDecoder().decode(inlineMaterial("FULL").get("content_base64").toString()),
                store.materialContent("t", material.id()));
        assertEquals(
                409,
                assertThrows(
                                PlatformException.class,
                                () -> store.materialContent("different-tenant", material.id()))
                        .status());
    }

    private PlatformStore.Entity prepareDecision(String caseId) {
        return prepareDecision(caseId, agent.userId());
    }

    private PlatformStore.Entity prepareDecision(String caseId, String creator) {
        var c = store.require("t", "case", caseId);
        String snapshot = c.data().get("evidence_snapshot_id").toString();
        Map<String, Object> decision =
                new LinkedHashMap<>(
                        Map.of(
                                "case_id",
                                caseId,
                                "evidence_snapshot_id",
                                snapshot,
                                "decision_status",
                                "DETERMINED",
                                "amount",
                                5));
        if (creator != null) decision.put("created_by", creator);
        store.create("t", "decision", "decision-1", decision, "worker", "DECISION");
        var review =
                store.create(
                        "t",
                        "review",
                        "review-1",
                        Map.of(
                                "case_id",
                                caseId,
                                "decision_id",
                                "decision-1",
                                "evidence_snapshot_id",
                                snapshot,
                                "status",
                                "PENDING"),
                        "worker",
                        "REVIEW");
        Map<String, Object> data = new LinkedHashMap<>(c.data());
        data.put("current_decision_id", "decision-1");
        data.put("review_id", review.id());
        data.put("status", "PENDING_REVIEW");
        store.update("t", "case", caseId, c.version(), data, "worker", "READY");
        return review;
    }
}

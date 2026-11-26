package com.delivery.harness.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.casework.ManualExceptionService;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

class ManualExceptionServiceTest {
    private final Principal actor =
            new Principal(
                    "t", "supervisor", Set.of("AGENT", "SUPERVISOR"), Set.of("*"), Set.of("*"));

    @Test
    void missingAndInsufficientRefundableBalancesCannotAuthorizeManualCashRefunds() {
        for (BigDecimal balance :
                new BigDecimal[] {null, BigDecimal.ZERO, new BigDecimal("4.00")}) {
            PlatformStore store = PlatformTestDatabase.memory();
            CaseService cases = cases(store, balance);
            var c =
                    cases.create(
                            actor, Map.of("order_id", "EXCEPTION", "complaint_type", "OVERTIME"));
            var service = new ManualExceptionService(store, cases, new BigDecimal("50"));
            PlatformException failure =
                    assertThrows(
                            PlatformException.class,
                            () -> service.propose(actor, c.id(), input(c, "REFUND", "5.00")));
            assertEquals(
                    balance == null ? "INSUFFICIENT_EVIDENCE" : "INVALID_REQUEST", failure.code());
            assertTrue(store.caseRecords("t", "decision", c.id(), 10).isEmpty());
        }
    }

    @Test
    void knownRefundableBalanceProducesCanonicalAdvisoryDecisionWithoutPaying() {
        PlatformStore store = PlatformTestDatabase.memory();
        CaseService cases = cases(store, new BigDecimal("5.00"));
        var c = cases.create(actor, Map.of("order_id", "EXCEPTION", "complaint_type", "OVERTIME"));
        var service = new ManualExceptionService(store, cases, new BigDecimal("50"));
        var decision = service.propose(actor, c.id(), input(c, "退款", "5.00"));
        assertEquals("REFUND", decision.data().get("suggested_method"));
        assertEquals(true, decision.data().get("advisory_only"));
        assertEquals("supervisor", decision.data().get("created_by"));
        assertEquals(
                "NOT_EXECUTED", store.require("t", "case", c.id()).data().get("payment_status"));
    }

    private CaseService cases(PlatformStore store, BigDecimal balance) {
        OrderSource source =
                new OrderSource("demo", "", "", "") {
                    @Override
                    public Snapshot query(Principal principal, String id) {
                        Snapshot result = super.query(principal, id);
                        result.order().setOrderAmount(new BigDecimal("25"));
                        result.order().setRefundableBalance(balance);
                        return result;
                    }
                };
        return new CaseService(store, source);
    }

    private Map<String, Object> input(PlatformStore.Entity c, String method, String amount) {
        return Map.of(
                "expected_case_version",
                c.version(),
                "evidence_snapshot_id",
                c.data().get("evidence_snapshot_id"),
                "method",
                method,
                "amount",
                amount,
                "reason",
                "Verified independent exception proposal",
                "authorization_basis",
                "Supervisor policy authority");
    }
}

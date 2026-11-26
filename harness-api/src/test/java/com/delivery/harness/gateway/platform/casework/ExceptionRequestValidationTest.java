package com.delivery.harness.gateway.platform.casework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.delivery.harness.common.util.JsonUtil;
import com.delivery.harness.gateway.platform.operations.ExceptionController;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.casework.ManualExceptionService;
import com.delivery.harness.platform.store.PlatformStore;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class ExceptionRequestValidationTest {
    @Test
    void wrongJsonTypesAndUnboundedTextFailBeforeAnyExceptionProposal() throws Exception {
        AtomicInteger proposals = new AtomicInteger();
        var service =
                new ManualExceptionService(null, null, new BigDecimal("50")) {
                    @Override
                    public PlatformStore.Entity propose(
                            Principal actor, String id, Map<String, Object> input) {
                        proposals.incrementAndGet();
                        throw new AssertionError(
                                "Invalid API input must not reach the proposal service");
                    }
                };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExceptionController(service)).build();
        var actor =
                new Principal("t", "supervisor", Set.of("SUPERVISOR"), Set.of("*"), Set.of("*"));
        List<Map.Entry<String, Object>> invalid =
                List.of(
                        Map.entry("amount", "5.00"),
                        Map.entry("amount", true),
                        Map.entry("amount", -1),
                        Map.entry("amount", 0.001),
                        Map.entry("expected_case_version", "1"),
                        Map.entry("reason", "x".repeat(2001)),
                        Map.entry("authorization_basis", 42),
                        Map.entry("method", "CASH"));
        for (Map.Entry<String, Object> value : invalid) {
            Map<String, Object> request =
                    new LinkedHashMap<>(
                            Map.of(
                                    "expected_case_version",
                                    1,
                                    "evidence_snapshot_id",
                                    "snapshot",
                                    "amount",
                                    5,
                                    "method",
                                    "REFUND",
                                    "reason",
                                    "Verified evidence",
                                    "authorization_basis",
                                    "Approved supervisor authority"));
            request.put(value.getKey(), value.getValue());
            mvc.perform(
                            post("/api/v2/cases/case/exceptions")
                                    .requestAttr("harness.principal", actor)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(JsonUtil.toJson(request)))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, proposals.get());
    }
}

package com.delivery.harness.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(DemoApiTestSupport.class)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CaseApiTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsReplaysAndRejectsDuplicateOpenCases() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/cases")
                        .header("Idempotency-Key", "req-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"ORD-MERCHANT-01\",\"complaint_type\":\"OVERTIME\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING_DECISION"))
                .andExpect(jsonPath("$.data.runs[0].adjudication.status").value("ELIGIBLE"))
                .andExpect(jsonPath("$.data.runs[0].adjudication.actionableAmount").value(5.00))
                .andExpect(jsonPath("$.data.runs[0].diagnosis.primaryCandidate").value("MERCHANT_PREP_EXCESS"))
                .andExpect(jsonPath("$.data.latestCustomerDraft.executionCompleted").value(false))
                .andReturn();

        String caseId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("caseId").asText();

        // Same Idempotency-Key replays cleanly with 200 OK.
        MvcResult replay = mockMvc.perform(post("/api/cases")
                        .header("Idempotency-Key", "req-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"ORD-MERCHANT-01\",\"complaint_type\":\"OVERTIME\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(caseId, objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("data").path("caseId").asText());

        // Different Idempotency-Key on same open (orderId, complaintType) returns 409 Conflict.
        mockMvc.perform(post("/api/cases")
                        .header("Idempotency-Key", "req-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"ORD-MERCHANT-01\",\"complaint_type\":\"OVERTIME\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void returnsNotFoundForUnknownOrderAndSeparatesBoundaryOutcomes() throws Exception {
        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"ORD-UNKNOWN\",\"complaint_type\":\"OVERTIME\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"ORD-INTRANSIT-01\",\"complaint_type\":\"OVERTIME\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.runs[0].adjudication.status").value("OUT_OF_SCOPE"))
                .andExpect(jsonPath("$.data.runs[0].adjudication.actionableAmount").doesNotExist());

        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"ORD-OVERCAP-01\",\"complaint_type\":\"MISSING_ITEM\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.runs[0].adjudication.status").value("NO_APPLICABLE_POLICY"))
                .andExpect(jsonPath("$.data.runs[0].adjudication.actionableAmount").doesNotExist());

        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"ORD-MISSING-TS-01\",\"complaint_type\":\"OVERTIME\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("WAITING_EVIDENCE"))
                .andExpect(jsonPath("$.data.runs[0].adjudication.status").value("NEEDS_EVIDENCE"))
                .andExpect(jsonPath("$.data.runs[0].adjudication.actionableAmount").doesNotExist());
    }

    @Test
    void runsFullAttestOverCapApproveAndReceiptClosureViaHttp() throws Exception {
        MvcResult createRes = mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"ORD-OVERCAP-01\",\"complaint_type\":\"DAMAGED\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("WAITING_EVIDENCE"))
                .andReturn();
        JsonNode created = objectMapper.readTree(createRes.getResponse().getContentAsString()).path("data");
        String caseId = created.path("caseId").asText();

        // Attest FULL -> 64.00 > 50.00 -> EXCEEDS_CAP_ESCALATE, no actionableAmount.
        MvcResult attestRes = mockMvc.perform(post("/api/cases/{caseId}/attestations", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidence_type\":\"DAMAGE_LEVEL\",\"value\":\"FULL\","
                                + "\"operator_id\":\"agent-1\",\"expected_case_version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ESCALATED"))
                .andExpect(jsonPath("$.data.runs[1].adjudication.status").value("EXCEEDS_CAP_ESCALATE"))
                .andExpect(jsonPath("$.data.runs[1].adjudication.actionableAmount").doesNotExist())
                .andExpect(jsonPath("$.data.runs[1].adjudication.calculatedAmount").value(64.00))
                .andReturn();
        JsonNode attested = objectMapper.readTree(attestRes.getResponse().getContentAsString()).path("data");
        String run2Id = attested.path("runs").get(1).path("runId").asText();

        // Approve without exception_reason -> 400.
        mockMvc.perform(post("/api/cases/{caseId}/decisions", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"" + run2Id + "\",\"expected_case_version\":2,"
                                + "\"action\":\"APPROVE\",\"operator_id\":\"sup-1\"}"))
                .andExpect(status().isBadRequest());

        // Approve with exception_reason -> APPROVED_PENDING_EXECUTION.
        mockMvc.perform(post("/api/cases/{caseId}/decisions", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"" + run2Id + "\",\"expected_case_version\":2,"
                                + "\"action\":\"APPROVE\",\"operator_id\":\"sup-1\","
                                + "\"exception_reason\":\"全损核验属实，特批退款\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED_PENDING_EXECUTION"));

        // Record receipt -> CLOSED + executed draft with actual 64.00 approved amount.
        mockMvc.perform(post("/api/cases/{caseId}/decisions", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run_id\":\"" + run2Id + "\",\"expected_case_version\":3,"
                                + "\"action\":\"RECORD_EXECUTION_RECEIPT\",\"operator_id\":\"ops-1\","
                                + "\"external_receipt_id\":\"PAY-2026-888\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.executionReceiptId").value("PAY-2026-888"))
                .andExpect(jsonPath("$.data.latestCustomerDraft.executionCompleted").value(true))
                .andExpect(jsonPath("$.data.latestCustomerDraft.message", containsString("已完成赔付处理")))
                .andExpect(jsonPath("$.data.latestCustomerDraft.message", containsString("64.00 元")))
                .andExpect(jsonPath("$.data.decisions[0].operatorIdentityMode")
                        .value("unauthenticated_operator_id"));

        // Inspect case + trace + active policy.
        mockMvc.perform(get("/api/cases/{caseId}", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseId").value(caseId));

        String traceId = attested.path("runs").get(1).path("traceId").asText();
        mockMvc.perform(get("/api/v1/observe/trace/{traceId}", traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.traceId").value(traceId));
    }

    @Test
    void escalatesKnownPromptInjectionAutomaticallyEvenInZeroLlmMode() throws Exception {
        mockMvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"ORD-IM-NEG-INJ-01\",\"complaint_type\":\"OVERTIME\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ESCALATED"))
                .andExpect(jsonPath("$.data.runs[0].untrustedTextDetected").value(true))
                .andExpect(jsonPath("$.data.runs[0].adjudication.status").value("ELIGIBLE"))
                .andExpect(jsonPath("$.data.runs[0].adjudication.actionableAmount").value(1.00));
    }
}

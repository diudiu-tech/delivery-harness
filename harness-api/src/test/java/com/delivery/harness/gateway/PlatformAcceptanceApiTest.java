package com.delivery.harness.gateway;

import com.delivery.harness.common.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties={
    "harness.execution.deterministic-only=true",
    "harness.security.tokens=[{\"token\":\"demo-reviewer\",\"tenant_id\":\"demo\",\"user_id\":\"independent-reviewer\",\"roles\":[\"AGENT\",\"OPERATOR\",\"REVIEWER\",\"SUPERVISOR\",\"QUALITY\",\"ADMIN\",\"AUDITOR\"],\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]},{\"token\":\"demo-other-tenant\",\"tenant_id\":\"other-tenant\",\"user_id\":\"other\",\"roles\":[\"AGENT\",\"AUDITOR\"],\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]}]"
})
class PlatformAcceptanceApiTest {
    @Autowired MockMvc mvc;
    private Map<String,Object> request(String path,Map<String,Object> body,String token,int status) throws Exception {
        var request=post("/api/v2"+path).contentType("application/json").content(JsonUtil.toJson(body));
        if(token!=null)request.header("Authorization","Bearer "+token);
        return JsonUtil.toMap(JsonUtil.fromJson(mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(),Map.class));
    }
    private Map<String,Object> getRecord(String path,String token,int status) throws Exception {
        var request=get("/api/v2"+path);if(token!=null)request.header("Authorization","Bearer "+token);
        return JsonUtil.fromJson(mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(),Map.class);
    }
    @Test void caseToFrozenRuleDecisionAndHumanApprovalDoesNotPay() throws Exception {
        // Bootstrap a real persisted demo runtime, not a stubbed v2 controller.
        getRecord("/runtime-configs",null,200);
        var c=request("/cases",Map.of("order_id","DEMO-002","complaint_type","OVERTIME","title","Acceptance severe overtime"),null,201);
        String caseId=c.get("id").toString();String body=JsonUtil.toJson(Map.of("case_id",caseId,"scenario","compensation_suggestion","runtime_config_id","runtime-default-v1","expected_case_version",c.get("version")));
        var execution=JsonUtil.fromJson(mvc.perform(post("/api/v2/executions").header("Idempotency-Key","acceptance-"+caseId).contentType("application/json").content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(),Map.class);
        String executionId=execution.get("id").toString();
        var repeated=JsonUtil.fromJson(mvc.perform(post("/api/v2/executions").header("Idempotency-Key","acceptance-"+caseId).contentType("application/json").content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(),Map.class);
        assertEquals(executionId,repeated.get("id"));
        Map<String,Object> finished=awaitExecution(executionId);
        assertTrue(Set.of("SUCCESS","DEGRADED").contains(finished.get("status")),finished.toString());
        c=getRecord("/cases/"+caseId,null,200);
        Map<String,Object> decision=JsonUtil.toMap(c.get("decision"));
        assertEquals("DETERMINED",decision.get("decision_status"));
        assertEquals(0,new java.math.BigDecimal(decision.get("amount").toString()).compareTo(new java.math.BigDecimal("20.00")));
        var review=JsonUtil.toMap(c.get("review"));String reviewId=review.get("id").toString();
        var claimed=request("/reviews/"+reviewId+"/claim",Map.of("expected_version",review.get("version")),"demo-reviewer",200);
        var approved=request("/reviews/"+reviewId+"/actions",Map.of("action","APPROVE","reason","Verified current evidence and policy", "expected_version",claimed.get("version"),"decision_id",decision.get("id"),"decision_version",decision.get("version"),"evidence_snapshot_id",c.get("evidence_snapshot_id")),"demo-reviewer",200);
        assertEquals("NOT_EXECUTED",approved.get("payment_status"));
        assertEquals("APPROVED",getRecord("/cases/"+caseId,null,200).get("review_status"));
        mvc.perform(get("/api/v2/cases/"+caseId).header("Authorization","Bearer demo-other-tenant")).andExpect(status().isNotFound());
        var export=getRecord("/cases/"+caseId+"/export","demo-reviewer",200);
        assertEquals(caseId,export.get("id"));assertFalse(JsonUtil.toJson(finished).contains("model_raw_output"));
    }
    @Test void unknownVersionsAndUntrustedIdentityCannotSilentlyBecomeDemoAuthority() throws Exception {
        mvc.perform(get("/api/v2/session").header("Authorization","Bearer unknown-token")).andExpect(status().isUnauthorized());
        var c=request("/cases",Map.of("order_id","DEMO-004","complaint_type","OVERTIME","submittedBy","spoofed-user"),null,201);
        assertEquals("demo-agent",c.get("created_by"));
        mvc.perform(post("/api/v2/executions").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(JsonUtil.toJson(Map.of("case_id",c.get("id"),"expected_case_version",c.get("version"),"runtime_config_id","missing-version")))).andExpect(status().isNotFound());
        mvc.perform(get("/workbench/index.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("案件工作台")));
    }
    private Map<String,Object> awaitExecution(String id) throws Exception {
        long limit=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        Map<String,Object> latest=null;
        while(System.nanoTime()<limit) {
            latest=getRecord("/executions/"+id,null,200);
            if(Set.of("SUCCESS","DEGRADED","FAILED","CANCELLED","TIMED_OUT").contains(latest.get("status")))return latest;
            Thread.sleep(20);
        }
        fail("Execution failed to reach a terminal state: "+latest);return latest;
    }
}

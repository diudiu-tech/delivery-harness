package com.delivery.harness.platform;

import com.delivery.harness.agent.workflow.WorkflowEngine;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.execution.ExecutionService;
import com.delivery.harness.platform.quality.*;
import com.delivery.harness.platform.store.*;
import com.delivery.harness.knowledge.rule.RuleBaseService;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionServiceTest {
    private PlatformStore store;private CaseService cases;private RuntimeConfigService runtimes;private WorkflowEngine engine;private ExecutionService executions;
    private final Principal actor=new Principal("tenant","agent",Set.of("AGENT"),Set.of("*"),Set.of("*"));
    @BeforeEach void setup(){
        store=PlatformTestDatabase.memory();OrderSource source=new OrderSource("demo","","","");cases=new CaseService(store,source);
        PolicyService policy=new PolicyService(store,new RuleBaseService());KnowledgeService knowledge=new KnowledgeService(store,policy);
        runtimes=new RuntimeConfigService(store,policy,knowledge,(model,messages,tools,temperature,tokens) -> "{}");engine=new WorkflowEngine();
        executions=new ExecutionService(store,cases,source,runtimes,engine,2,50,15,new BigDecimal("10"));
    }
    @AfterEach void stop(){executions.stop();}
    @Test void concurrentTwentyRequestsShareOneDurableExecution() throws Exception {
        engine.registerHandler("compensation_suggestion",(input,execution) -> Map.of("authoritative_amount",5,"decision_status","DETERMINED","suggested_method","CASH"));
        var c=cases.create(actor,Map.of("order_id","ORDER-1","complaint_type","OVERTIME"));Map<String,Object> request=Map.of("case_id",c.id(),"scenario","compensation_suggestion","expected_case_version",c.version());
        ExecutorService callers=Executors.newFixedThreadPool(20);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<String>> results=new ArrayList<>();for(int i=0;i<20;i++)results.add(callers.submit(() -> {start.await();return executions.submit(actor,request,"same-key").id();}));start.countDown();
            Set<String> ids=new HashSet<>();for(Future<String> result:results)ids.add(result.get(10,TimeUnit.SECONDS));assertEquals(1,ids.size());
            String id=ids.iterator().next();awaitTerminal(id);assertEquals(1,store.list(actor.tenantId(),"execution").size());assertEquals(1,store.list(actor.tenantId(),"decision").size());
            Map<String,Object> changed=new LinkedHashMap<>(request);changed.put("damage_level","FULL");
            assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(PlatformException.class,() -> executions.submit(actor,changed,"same-key")).code());
        }finally{callers.shutdownNow();}
    }
    @Test void upstreamScopeChangesCannotLeakNewStationFactsThroughAnOldCase() {
        OrderSource source=new OrderSource("demo","","",""){int calls;@Override public Snapshot query(Principal principal,String orderId){Snapshot result=super.query(principal,orderId);if(++calls>1)result.order().setCityCode("OTHER-CITY");return result;}};
        cases=new CaseService(store,source);executions.stop();executions=new ExecutionService(store,cases,source,runtimes,engine,2,50,15,new BigDecimal("10"));
        var c=cases.create(actor,Map.of("order_id","ORDER-SCOPE","complaint_type","OVERTIME"));String snapshot=c.data().get("evidence_snapshot_id").toString();
        var failure=assertThrows(PlatformException.class,() -> executions.submit(actor,Map.of("case_id",c.id(),"expected_case_version",c.version()),"scope"));assertEquals("ORDER_SCOPE_CHANGED",failure.code());assertEquals(snapshot,cases.require(actor,c.id()).data().get("evidence_snapshot_id"));assertTrue(store.list(actor.tenantId(),"execution").isEmpty());
    }
    @Test void materialRejectionDuringSourceFetchCannotFreezeAStaleVerifiedFlag() throws Exception {
        final String[] materialId={null};final boolean[] rejectDuringLookup={false};Principal reviewer=new Principal("tenant","reviewer",Set.of("REVIEWER"),Set.of("*"),Set.of("*"));
        OrderSource source=new OrderSource("demo","","",""){@Override public Snapshot query(Principal principal,String orderId){if(rejectDuringLookup[0]){rejectDuringLookup[0]=false;cases.reviewMaterial(reviewer,materialId[0],Map.of("expected_version",2,"action","REJECT","reason","evidence contradicted"));}return super.query(principal,orderId);}};
        cases=new CaseService(store,source);executions.stop();executions=new ExecutionService(store,cases,source,runtimes,engine,2,50,15,new BigDecimal("10"));
        var c=cases.create(actor,Map.of("order_id","ORDER-DAMAGE","complaint_type","DAMAGED"));var material=cases.addEvidence(actor,c.id(),CaseServiceTest.inlineMaterial("FULL"));materialId[0]=material.id();cases.reviewMaterial(reviewer,material.id(),Map.of("expected_version",1,"action","VERIFY","reason","inspected frozen content"));var verified=cases.require(actor,c.id());rejectDuringLookup[0]=true;
        engine.registerHandler("compensation_suggestion",(input,execution) -> {Map<String,Object> output=new LinkedHashMap<>();output.put("decision_status",RuntimeBinding.damageEvidenceVerified()?"DETERMINED":"INSUFFICIENT_EVIDENCE");output.put("authoritative_amount",RuntimeBinding.damageEvidenceVerified()?50:null);return output;});
        var e=executions.submit(actor,Map.of("case_id",c.id(),"scenario","compensation_suggestion","damage_level","FULL","expected_case_version",verified.version()+1),"raced-material");awaitTerminal(e.id());var result=executions.require(actor,e.id());assertEquals(false,result.data().get("damage_evidence_verified"));var decision=store.require(actor.tenantId(),"decision",result.data().get("decision_id").toString());assertEquals("INSUFFICIENT_EVIDENCE",decision.data().get("decision_status"));assertNull(decision.data().get("amount"));
    }
    @Test void pendingPaymentsBlockCreatingANewExecution(){var c=cases.create(actor,Map.of("order_id","ORDER-PAYMENT","complaint_type","OVERTIME"));Map<String,Object> data=new LinkedHashMap<>(c.data());data.put("payment_status","UNKNOWN");var locked=store.update(actor.tenantId(),"case",c.id(),c.version(),data,"funds","PAYMENT_UNKNOWN");assertThrows(PlatformException.class,() -> executions.submit(actor,Map.of("case_id",c.id(),"expected_case_version",locked.version()),"payment-lock"));assertTrue(store.list(actor.tenantId(),"execution").isEmpty());assertEquals("UNKNOWN",cases.require(actor,c.id()).data().get("payment_status"));}
    @Test void globalAdmissionIsBoundedAndQueuedTenantsAlternateFairly() throws Exception {
        executions.stop();OrderSource source=new OrderSource("demo","","","");executions=new ExecutionService(store,cases,source,runtimes,engine,1,4,15,new BigDecimal("10"));
        Principal other=new Principal("other","agent-b",Set.of("AGENT"),Set.of("*"),Set.of("*"));CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);List<String> order=Collections.synchronizedList(new ArrayList<>());
        engine.registerHandler("abnormal_order_analysis",(input,execution) -> {String id=input.get("order_id").toString();order.add(id);if("A1".equals(id)){entered.countDown();try{release.await();}catch(InterruptedException ignored){}}return Map.of("decision_status","DETERMINED");});
        List<PlatformStore.Entity> tasks=new ArrayList<>();tasks.add(submitFor(actor,"A1"));assertTrue(entered.await(5,TimeUnit.SECONDS));tasks.add(submitFor(actor,"A2"));tasks.add(submitFor(actor,"A3"));tasks.add(submitFor(other,"B1"));tasks.add(submitFor(other,"B2"));
        var extra=cases.create(actor,Map.of("order_id","A4","complaint_type","OVERTIME"));assertEquals(429,assertThrows(PlatformException.class,() -> executions.submit(actor,Map.of("case_id",extra.id(),"expected_case_version",extra.version()),"A4")).status());release.countDown();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<until && store.list("_SYSTEM","execution_global_quota").get(0).data().get("active").toString().equals("0")==false)Thread.sleep(20);
        assertEquals(List.of("A1","B1","A2","B2","A3"),order);assertEquals(0,((Number)store.require("_SYSTEM","execution_global_quota","online").data().get("active")).intValue());
    }
    private PlatformStore.Entity submitFor(Principal principal,String orderId){var c=cases.create(principal,Map.of("order_id",orderId,"complaint_type","OVERTIME"));return executions.submit(principal,Map.of("case_id",c.id(),"expected_case_version",c.version()),orderId);}
    @Test void dependencyFailurePersistsCompletedAndFailedSteps() throws Exception {
        engine.registerHandler("abnormal_order_analysis",(input,execution) -> {
            execution.getSteps().add(com.delivery.harness.common.dto.WorkflowExecution.StepExecution.builder().order(1).name("evidence read").stepType("tool_call").status("SUCCESS").build());
            throw new com.delivery.harness.common.exception.ToolException("eta_query","dependent source unavailable");
        });
        var c=cases.create(actor,Map.of("order_id","ORDER-1","complaint_type","OVERTIME"));var e=executions.submit(actor,Map.of("case_id",c.id(),"expected_case_version",c.version()),"fail-partial");awaitTerminal(e.id());
        var failed=executions.require(actor,e.id());assertEquals("FAILED",failed.data().get("status"));assertTrue(failed.data().get("steps") instanceof List<?>);assertFalse(((List<?>)failed.data().get("steps")).isEmpty());assertEquals("OPEN",cases.require(actor,c.id()).data().get("status"));
    }
    @Test void cancelledExecutionCannotBeOverwrittenByLateWorkerResult() throws Exception {
        CountDownLatch entered=new CountDownLatch(1);CountDownLatch released=new CountDownLatch(1);
        engine.registerHandler("compensation_suggestion",(input,execution) -> {entered.countDown();while(released.getCount()>0){try{released.await();}catch(InterruptedException ignored){}}return Map.of("authoritative_amount",5,"decision_status","DETERMINED");});
        var c=cases.create(actor,Map.of("order_id","ORDER-1","complaint_type","OVERTIME"));var e=executions.submit(actor,Map.of("case_id",c.id(),"scenario","compensation_suggestion","expected_case_version",c.version()),"cancel");
        assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals("CANCELLED",executions.cancel(actor,e.id()).data().get("status"));released.countDown();
        Thread.sleep(150);assertEquals("CANCELLED",executions.require(actor,e.id()).data().get("status"));assertTrue(store.list(actor.tenantId(),"decision").isEmpty());
    }
    @Test void expiredWorkerLeaseRecoversUsingFrozenEvidenceAndConfiguration() throws Exception {
        engine.registerHandler("abnormal_order_analysis",(input,execution) -> {assertNotNull(RuntimeBinding.evidenceOrder());assertTrue(RuntimeBinding.deterministicOnly());return Map.of("decision_status","DETERMINED");});
        var c=cases.create(actor,Map.of("order_id","ORDER-1","complaint_type","OVERTIME"));Map<String,Object> config=runtimes.resolve(actor.tenantId(),"runtime-default-v1");
        Map<String,Object> data=new LinkedHashMap<>();data.put("case_id",c.id());data.put("scenario","abnormal_order_analysis");data.put("status","RUNNING");data.put("fence","dead-worker");data.put("lease_until",Instant.now().minusSeconds(60).toString());data.put("deadline_at",Instant.now().plusSeconds(10).toString());
        data.put("runtime_config",config);data.put("evidence_snapshot_id",c.data().get("evidence_snapshot_id"));data.put("input",Map.of("order_id","ORDER-1"));data.put("submitted_by",actor.userId());data.put("attempt",1);
        store.create(actor.tenantId(),"execution","restart-run",data,"dead-worker","QUEUED");Map<String,Object> cd=new LinkedHashMap<>(c.data());cd.put("current_execution_id","restart-run");cd.put("status","ANALYZING");store.update(actor.tenantId(),"case",c.id(),c.version(),cd,"dead-worker","STARTED");
        executions.start();awaitTerminal("restart-run");var recovered=store.require(actor.tenantId(),"execution","restart-run");assertEquals("SUCCESS",recovered.data().get("status"));assertEquals(2,((Number)recovered.data().get("attempt")).intValue());assertEquals(1,store.list(actor.tenantId(),"decision").size());
    }
    @Test void deadlineMonitorStopsStalledWorker() throws Exception {
        CountDownLatch entered=new CountDownLatch(1);CountDownLatch release=new CountDownLatch(1);
        engine.registerHandler("abnormal_order_analysis",(input,execution) -> {entered.countDown();try{release.await();}catch(InterruptedException ignored){}return Map.of("decision_status","DETERMINED");});
        var c=cases.create(actor,Map.of("order_id","ORDER-1","complaint_type","OVERTIME"));var e=executions.submit(actor,Map.of("case_id",c.id(),"expected_case_version",c.version()),"deadline");assertTrue(entered.await(5,TimeUnit.SECONDS));
        var running=store.require(actor.tenantId(),"execution",e.id());Map<String,Object> data=new LinkedHashMap<>(running.data());data.put("deadline_at",Instant.now().minusSeconds(1).toString());store.update(actor.tenantId(),"execution",e.id(),running.version(),data,"test","DEADLINE_TEST");
        executions.start();awaitTerminal(e.id());assertEquals("TIMED_OUT",executions.require(actor,e.id()).data().get("status"));release.countDown();
    }
    private void awaitTerminal(String id) throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<until){if(Set.of("SUCCESS","DEGRADED","FAILED","CANCELLED","TIMED_OUT").contains(executions.require(actor,id).data().get("status")))return;Thread.sleep(25);}fail("Execution did not reach terminal state");}
}

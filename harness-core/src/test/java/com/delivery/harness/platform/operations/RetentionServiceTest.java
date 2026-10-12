package com.delivery.harness.platform.operations;

import com.delivery.harness.platform.PlatformTestDatabase;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetentionServiceTest {
    private PlatformStore store;private CaseService cases;private Principal author,approver;private String caseId;
    @BeforeEach void setup(){
        store=PlatformTestDatabase.memory();cases=new CaseService(store,new OrderSource("demo","","",""));
        author=new Principal("test","author",Set.of("AGENT","ADMIN","AUDITOR"),Set.of("*"),Set.of("*"));
        approver=new Principal("test","approver",Set.of("ADMIN","AUDITOR"),Set.of("*"),Set.of("*"));
        var c=cases.create(author,Map.of("order_id","DEMO-002","complaint_type","OVERTIME"));caseId=c.id();
        Map<String,Object> data=new LinkedHashMap<>(c.data());data.put("status","CLOSED");data.put("closed_at",Instant.now().minusSeconds(200*86400L).toString());
        store.update("test","case",caseId,c.version(),data,"reviewer","CASE_CLOSED");
    }
    private Map<String,Object> approval(PlatformStore.Entity plan){return Map.of("expected_version",plan.version(),"graph_hash",plan.data().get("graph_hash"),"reason","retention approved after review");}
    @Test void independentApprovalDeletesCompleteGraphAndKeepsReceiptAndAudit(){
        var service=new RetentionService(store,cases,true,180);var plan=service.plan(author,Map.of("case_id",caseId,"reason","retention due"));
        assertEquals("PENDING_APPROVAL",plan.data().get("status"));assertThrows(PlatformException.class,()->service.approve(author,plan.id(),approval(plan)));
        var receipt=service.approve(approver,plan.id(),approval(plan));assertEquals("COMPLETED",receipt.data().get("status"));
        assertTrue(store.get("test","case",caseId).isEmpty());assertTrue(store.list("test","order_snapshot").isEmpty());
        assertEquals("COMPLETED",service.get(approver,plan.id()).data().get("status"));assertTrue(store.audits("test").stream().anyMatch(a->"RETENTION_RECORD_DELETED".equals(a.get("action"))));
    }
    @Test void defaultDryRunAndHoldNeverDeleteData(){
        var disabled=new RetentionService(store,cases,false,180);var plan=disabled.plan(author,Map.of("case_id",caseId,"reason","preview"));
        assertThrows(PlatformException.class,()->disabled.approve(approver,plan.id(),approval(plan)));
        var enabled=new RetentionService(store,cases,true,180);enabled.hold(approver,caseId,Map.of("locked",true,"reason","investigation hold"));
        var blocked=enabled.plan(author,Map.of("case_id",caseId,"reason","preview"));assertEquals("BLOCKED",blocked.data().get("status"));assertTrue(store.get("test","case",caseId).isPresent());
        assertThrows(PlatformException.class,()->enabled.approve(approver,plan.id(),approval(plan)));
    }
    @Test void changedReferenceGraphRequiresNewPlan(){
        var service=new RetentionService(store,cases,true,180);var plan=service.plan(author,Map.of("case_id",caseId,"reason","preview"));
        store.create("test","feedback","late-feedback",Map.of("case_id",caseId),"user","LATE_FEEDBACK");
        assertThrows(PlatformException.class,()->service.approve(approver,plan.id(),approval(plan)));assertTrue(store.get("test","case",caseId).isPresent());
    }
    @Test void evaluationOrFinancialReferencesBlockPurge(){
        var service=new RetentionService(store,cases,true,180);
        store.create("test","eval_dataset","retained-sample",Map.of("samples",List.of(Map.of("input",Map.of("order_id","DEMO-002")))),"quality","DATASET");
        assertEquals("BLOCKED",service.plan(author,Map.of("case_id",caseId,"reason","preview")).data().get("status"));
        store.create("test","compensation","ledger",Map.of("case_id",caseId),"funds","LEDGER");
        assertEquals(2,((List<?>)service.plan(author,Map.of("case_id",caseId,"reason","preview")).data().get("blocked_reasons")).size());
    }

    @Test void concurrentReferenceCreationForcesRetentionToRefreshItsGraph() throws Exception {
        var service=new RetentionService(store,cases,true,180);var plan=service.plan(author,Map.of("case_id",caseId,"reason","preview"));
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var deleting=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer=pool.submit(()->store.transact(tx->{tx.lock("test","case",caseId);locked.countDown();
                try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test lock timed out"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return tx.create("test","feedback","concurrent-feedback",Map.of("case_id",caseId),"quality","FEEDBACK_SUBMITTED");}));
            assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var deletion=pool.submit(()->{deleting.countDown();return service.approve(approver,plan.id(),approval(plan));});
            assertTrue(deleting.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,()->deletion.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();writer.get(5,java.util.concurrent.TimeUnit.SECONDS);
            var rejected=assertThrows(java.util.concurrent.ExecutionException.class,()->deletion.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(PlatformException.class,rejected.getCause());assertEquals(409,((PlatformException)rejected.getCause()).status());
            assertTrue(store.get("test","case",caseId).isPresent());assertTrue(store.get("test","feedback","concurrent-feedback").isPresent());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void referencesCannotBeCreatedAfterParentRetirement() {
        var service=new RetentionService(store,cases,true,180);var plan=service.plan(author,Map.of("case_id",caseId,"reason","preview"));service.approve(approver,plan.id(),approval(plan));
        assertThrows(PlatformException.class,()->store.transact(tx->{tx.lock("test","case",caseId);return tx.create("test","feedback","too-late",Map.of("case_id",caseId),"quality","FEEDBACK_SUBMITTED");}));
        assertTrue(store.get("test","feedback","too-late").isEmpty());
    }
}

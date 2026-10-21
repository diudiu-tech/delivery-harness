package com.delivery.harness.platform.compensation;

import com.delivery.harness.platform.PlatformTestDatabase;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.casework.ManualExceptionService;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import static org.junit.jupiter.api.Assertions.*;

class CompensationLedgerTest {
    private PlatformStore store;
    private DataSource source;
    private CaseService cases;
    private Principal actor;
    private String caseId;
    private final AtomicInteger calls = new AtomicInteger();
    private PaymentChannel channel;
    private final String secret="demonstration-test-secret-of-at-least-32-characters";
    @BeforeEach void setup() {
        source=PlatformTestDatabase.source("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        PlatformTestDatabase.migrate(source);store=new PlatformStore(source); cases=new CaseService(store,new OrderSource("demo","","",""));
        actor=new Principal("test","operator",Set.of("AGENT","SUPERVISOR","FUNDS","PAYMENT_CALLBACK"),Set.of("*"),Set.of("*"));
        PlatformStore.Entity c=cases.create(actor,Map.of("order_id","DEMO-002","complaint_type","OVERTIME"));caseId=c.id();
        store.create("test","policy","approved-policy",Map.of("state","PUBLISHED"),"publisher","POLICY_PUBLISHED");
        store.create("test","decision","approved",Map.of("case_id",caseId,"amount",new BigDecimal("20.00"),"currency","CNY","method","REFUND","decision_status","DETERMINED","policy_version","approved-policy","evidence_snapshot_id",c.data().get("evidence_snapshot_id")),"worker","DECISION");
        store.create("test","review","review",Map.of("case_id",caseId,"decision_id","approved","status","APPROVED"),"reviewer","APPROVED");
        Map<String,Object> data=new LinkedHashMap<>(c.data());data.put("current_decision_id","approved");data.put("review_id","review");data.put("status","APPROVED");data.put("review_status","APPROVED");store.update("test","case",caseId,c.version(),data,"reviewer","APPROVED");
        channel=new PaymentChannel(){public Result submit(String id,Map<String,Object> approved){calls.incrementAndGet();return new Result("UNKNOWN",null,"network timeout");}public Result query(String id,Map<String,Object> approved){return new Result("SUCCEEDED","external-1","queried");}public String name(){return "TEST";}public boolean synthetic(){return true;}};
    }
    private Map<String,Object> request(String key){return Map.of("case_id",caseId,"compensation_id",key,"decision_id","approved","decision_version",1);}
    @Test void disabledExecutionCannotMoveMoney() {
        var ledger=new CompensationLedgerService(store,cases,channel,false,secret);
        assertThrows(PlatformException.class,()->ledger.submit(actor,request("business-1"))); assertEquals(0,calls.get());
    }
    @Test void timeoutIsReconciledWithoutResubmittingAndDecisionCannotBePaidTwice() {
        var ledger=new CompensationLedgerService(store,cases,channel,true,secret);
        assertEquals("UNKNOWN",ledger.submit(actor,request("business-1")).data().get("status"));
        assertEquals("UNKNOWN",ledger.submit(actor,request("business-1")).data().get("status")); assertEquals(1,calls.get());
        assertThrows(PlatformException.class,()->ledger.submit(actor,request("business-2")));
        assertEquals("SUCCEEDED",ledger.reconcile(actor,"business-1").data().get("status")); assertEquals(1,calls.get());
    }
    @Test void signedCallbackIsIdempotentAndCannotOverwriteTerminalSuccess() {
        var ledger=new CompensationLedgerService(store,cases,channel,true,secret);ledger.submit(actor,request("business-1"));
        String timestamp=Instant.now().toString();Map<String,Object> payload=Map.of("status","SUCCEEDED","amount","20.00","currency","CNY","external_id","channel-1");
        String signature=CompensationLedgerService.sign(secret,"test\n"+timestamp+"\nevent-1\nbusiness-1\n"+PlatformStore.hash(payload));
        var accepted=ledger.callback(actor,"business-1","event-1",timestamp,signature,payload);
        var duplicate=ledger.callback(actor,"business-1","event-1",timestamp,signature,payload);
        assertEquals(accepted.version(),duplicate.version());assertEquals("SUCCEEDED",duplicate.data().get("status"));
        assertThrows(PlatformException.class,()->ledger.callback(actor,"business-1","event-2",timestamp,"bad-signature",payload));
        Map<String,Object> failed=Map.of("status","FAILED","amount","20.00","currency","CNY");
        String conflict=CompensationLedgerService.sign(secret,"test\n"+timestamp+"\nevent-3\nbusiness-1\n"+PlatformStore.hash(failed));
        assertThrows(PlatformException.class,()->ledger.callback(actor,"business-1","event-3",timestamp,conflict,failed));
    }
    @Test void updatedEvidenceInvalidatesApprovalAndExceptionsRequireIndependentApproval() {
        var ledger=new CompensationLedgerService(store,cases,channel,true,secret);
        var current=store.require("test","case",caseId);Map<String,Object> data=new LinkedHashMap<>(current.data());data.put("evidence_snapshot_id","new-snapshot");store.update("test","case",caseId,current.version(),data,"reviewer","EVIDENCE_UPDATE");
        assertThrows(PlatformException.class,()->ledger.submit(actor,request("business-1")));assertEquals(0,calls.get());
        // Restore evidence and propose an append-only manual exception.
        var changed=store.require("test","case",caseId);data=new LinkedHashMap<>(changed.data());String snapshot=store.require("test","decision","approved").data().get("evidence_snapshot_id").toString();data.put("evidence_snapshot_id",snapshot);var restored=store.update("test","case",caseId,changed.version(),data,"reviewer","RESTORED");
        var exceptions=new ManualExceptionService(store,cases,new BigDecimal("50"));
        var proposed=exceptions.propose(actor,caseId,Map.of("expected_case_version",restored.version(),"evidence_snapshot_id",snapshot,"amount","10.00","method","REFUND","reason","verified exceptional evidence","authorization_basis","supervisor exception policy"));
        var c=store.require("test","case",caseId);String reviewId=c.data().get("review_id").toString();
        var claimed=cases.claim(actor,reviewId,1);
        assertThrows(PlatformException.class,()->cases.act(actor,reviewId,Map.of("expected_version",claimed.version(),"action","APPROVE","reason","self approval","decision_id",proposed.id(),"decision_version",proposed.version(),"evidence_snapshot_id",snapshot)));
        assertEquals("IN_REVIEW",store.require("test","review",reviewId).data().get("status"));
    }

    @Test void staleChannelBalanceAndChangedIdempotencyBodyNeverDispatch() {
        PaymentChannel stale = new PaymentChannel() {
            public Eligibility eligibility(String tenant,String order){return new Eligibility(order,"CNY","DELIVERED",new BigDecimal("10"),new BigDecimal("50"),"v1",Instant.now().minusSeconds(600));}
            public Result submit(String id,Map<String,Object> data){calls.incrementAndGet();return new Result("SUCCEEDED","bad","should not happen");}
            public Result query(String id,Map<String,Object> data){return new Result("UNKNOWN",null,"unknown");}
            public String name(){return "TEST";}public boolean synthetic(){return false;}
        };
        assertThrows(PlatformException.class,()->new CompensationLedgerService(store,cases,stale,true,secret).submit(actor,request("stale")));
        assertEquals(0,calls.get());assertTrue(store.list("test","compensation").isEmpty());
        var ledger=new CompensationLedgerService(store,cases,channel,true,secret);ledger.submit(actor,request("business-1"));
        assertThrows(PlatformException.class,()->ledger.submit(actor,Map.of("case_id",caseId,"compensation_id","business-1","decision_id","another","decision_version",1)));
        assertEquals(1,calls.get());
    }

    @Test void separateCasesForSameOrderShareCumulativeCeiling() {
        var ledger=new CompensationLedgerService(store,cases,channel,true,secret);ledger.submit(actor,request("first"));
        var second=cases.create(actor,Map.of("order_id","DEMO-002","complaint_type","DAMAGED"));
        store.create("test","decision","second-decision",Map.of("case_id",second.id(),"amount",new BigDecimal("31.00"),"currency","CNY","method","COUPON","decision_status","DETERMINED","policy_version","approved-policy","evidence_snapshot_id",second.data().get("evidence_snapshot_id")),"worker","DECISION");
        store.create("test","review","second-review",Map.of("case_id",second.id(),"decision_id","second-decision","status","APPROVED"),"reviewer","APPROVED");
        Map<String,Object> data=new LinkedHashMap<>(second.data());data.put("current_decision_id","second-decision");data.put("review_id","second-review");data.put("review_status","APPROVED");store.update("test","case",second.id(),second.version(),data,"reviewer","APPROVED");
        assertThrows(PlatformException.class,()->ledger.submit(actor,Map.of("case_id",second.id(),"compensation_id","second","decision_id","second-decision","decision_version",1)));
        assertEquals(1,calls.get());assertEquals(1,store.list("test","compensation").size());
    }

    @Test void oldCallbackDoesNotOverwriteCurrentPaymentAndTenantIsSigned() {
        var ledger=new CompensationLedgerService(store,cases,channel,true,secret);var old=ledger.submit(actor,request("old"));
        Map<String,Object> oldData=new LinkedHashMap<>(old.data());oldData.put("status","FAILED");store.update("test","compensation",old.id(),old.version(),oldData,"channel","FAILED");
        var c=store.require("test","case",caseId);Map<String,Object> cd=new LinkedHashMap<>(c.data());cd.put("compensation_id","new-payment");cd.put("current_decision_id","new-decision");cd.put("payment_status","UNKNOWN");store.update("test","case",caseId,c.version(),cd,"funds","NEW_RESERVATION");
        String timestamp=Instant.now().toString();Map<String,Object> payload=Map.of("status","FAILED","amount","20.00","currency","CNY");
        String signature=CompensationLedgerService.sign(secret,"test\n"+timestamp+"\nlate-event\nold\n"+PlatformStore.hash(payload));
        ledger.callback(actor,"old","late-event",timestamp,signature,payload);
        assertEquals("UNKNOWN",store.require("test","case",caseId).data().get("payment_status"));
        var anotherTenant=new Principal("another","callback",Set.of("PAYMENT_CALLBACK"),Set.of("*"),Set.of("*"));
        var rejected=assertThrows(PlatformException.class,()->ledger.callback(anotherTenant,"old","late-event",timestamp,signature,payload));assertEquals(401,rejected.status());
    }

    @Test void policyWithdrawnAfterApprovalBlocksFunds() {
        var policy=store.require("test","policy","approved-policy");store.update("test","policy",policy.id(),policy.version(),Map.of("state","WITHDRAWN"),"publisher","POLICY_WITHDRAWN");
        assertThrows(PlatformException.class,()->new CompensationLedgerService(store,cases,channel,true,secret).submit(actor,request("withdrawn")));
        assertEquals(0,calls.get());assertTrue(store.list("test","compensation").isEmpty());
    }

    @Test void recoveryReleasesUndispatchedReservationAfterPolicyWithdrawal() {
        var c=store.require("test","case",caseId);
        store.create("test","compensation","pending",Map.of("case_id",caseId,"order_id","DEMO-002","decision_id","approved","decision_version",1,"evidence_snapshot_id",c.data().get("evidence_snapshot_id"),"amount","20.00","currency","CNY","method","REFUND","status","PENDING"),"funds","RESERVED");
        Map<String,Object> cd=new LinkedHashMap<>(c.data());cd.put("compensation_id","pending");cd.put("payment_status","PENDING");store.update("test","case",caseId,c.version(),cd,"funds","RESERVED");
        var policy=store.require("test","policy","approved-policy");store.update("test","policy",policy.id(),policy.version(),Map.of("state","WITHDRAWN"),"publisher","WITHDRAWN");
        new CompensationLedgerService(store,cases,channel,true,secret).recoverOutbox();
        assertEquals(0,calls.get());assertEquals("FAILED",store.require("test","compensation","pending").data().get("status"));
        assertEquals("FAILED",store.require("test","case",caseId).data().get("payment_status"));
    }

    @Test void stalePendingObservationCannotReleaseConcurrentDispatch() throws Exception {
        var c=store.require("test","case",caseId);
        store.create("test","compensation","pending",Map.of("case_id",caseId,"order_id","DEMO-002","decision_id","approved","decision_version",1,"evidence_snapshot_id",c.data().get("evidence_snapshot_id"),"amount","20.00","currency","CNY","method","REFUND","status","PENDING"),"funds","RESERVED");
        Map<String,Object> cd=new LinkedHashMap<>(c.data());cd.put("compensation_id","pending");cd.put("payment_status","PENDING");store.update("test","case",caseId,c.version(),cd,"funds","RESERVED");
        CountDownLatch pendingObserved=new CountDownLatch(1),dispatched=new CountDownLatch(1),cleanupComplete=new CountDownLatch(1);
        AtomicInteger pendingReads=new AtomicInteger();
        PlatformStore observingStore=new PlatformStore(source) {
            @Override public Optional<Entity> get(String tenant,String kind,String id) {
                Optional<Entity> snapshot=super.get(tenant,kind,id);
                if("compensation".equals(kind)&&"pending".equals(id)&&snapshot.isPresent()
                        &&"PENDING".equals(snapshot.get().data().get("status"))&&pendingReads.incrementAndGet()==2) {
                    pendingObserved.countDown();await(dispatched);
                }
                return snapshot;
            }
        };
        PaymentChannel stale=new PaymentChannel() {
            public Eligibility eligibility(String tenant,String order){return new Eligibility(order,"CNY","DELIVERED",new BigDecimal("50"),new BigDecimal("50"),"stale",Instant.now().minusSeconds(600));}
            public Result submit(String id,Map<String,Object> data){throw new AssertionError("Stale authorization must not dispatch");}
            public Result query(String id,Map<String,Object> data){return new Result("UNKNOWN",null,"unknown");}
            public String name(){return "STALE";}public boolean synthetic(){return true;}
        };
        PaymentChannel winning=new PaymentChannel() {
            public Result submit(String id,Map<String,Object> data){calls.incrementAndGet();dispatched.countDown();await(cleanupComplete);return new Result("UNKNOWN",null,"awaiting reconciliation");}
            public Result query(String id,Map<String,Object> data){return new Result("UNKNOWN",null,"unknown");}
            public String name(){return "WINNER";}public boolean synthetic(){return true;}
        };
        var losingLedger=new CompensationLedgerService(observingStore,cases,stale,true,secret);
        var winningLedger=new CompensationLedgerService(store,cases,winning,true,secret);
        FutureTask<PlatformStore.Entity> loser=new FutureTask<>(()->losingLedger.submit(actor,request("pending")));
        FutureTask<PlatformStore.Entity> winner=new FutureTask<>(()->winningLedger.submit(actor,request("pending")));
        new Thread(loser,"stale-authorization").start();await(pendingObserved);new Thread(winner,"valid-dispatch").start();
        try {
            assertEquals("SUBMITTED",loser.get(5,TimeUnit.SECONDS).data().get("status"));
            assertEquals("SUBMITTED",store.require("test","case",caseId).data().get("payment_status"));
            assertFalse(store.audits("test").stream().anyMatch(event->"COMPENSATION_RESERVATION_RELEASED".equals(event.get("action"))));
        } finally {cleanupComplete.countDown();}
        assertEquals("UNKNOWN",winner.get(5,TimeUnit.SECONDS).data().get("status"));assertEquals(1,calls.get());
        assertEquals("UNKNOWN",winningLedger.failUndispatched(actor,"pending",request("pending"),"stale authorization").data().get("status"));
        assertEquals("UNKNOWN",store.require("test","case",caseId).data().get("payment_status"));
    }

    @Test void concurrentCasesCannotReserveFortyAgainstThirtyRemaining() throws Exception {
        var second=cases.create(actor,Map.of("order_id","DEMO-002","complaint_type","DAMAGED"));
        store.create("test","policy","second-policy",Map.of("state","PUBLISHED"),"publisher","POLICY_PUBLISHED");
        store.create("test","decision","second-decision",Map.of("case_id",second.id(),"amount",new BigDecimal("20.00"),"currency","CNY","method","REFUND","decision_status","DETERMINED","policy_version","second-policy","evidence_snapshot_id",second.data().get("evidence_snapshot_id")),"worker","DECISION");
        store.create("test","review","second-review",Map.of("case_id",second.id(),"decision_id","second-decision","status","APPROVED"),"reviewer","APPROVED");
        Map<String,Object> data=new LinkedHashMap<>(second.data());data.put("current_decision_id","second-decision");data.put("review_id","second-review");data.put("review_status","APPROVED");store.update("test","case",second.id(),second.version(),data,"reviewer","APPROVED");
        String guardId=PlatformStore.hash(Map.of("order_id","DEMO-002"));
        store.create("test","compensation_order",guardId,Map.of("order_id","DEMO-002","reserved_total","0.00"),"funds","GUARD");
        CountDownLatch secondReady=new CountDownLatch(1),firstDispatched=new CountDownLatch(1);
        // Force the former scan-before-CAS interleaving when a guard is read without a lock.
        PlatformStore guardedStore=new PlatformStore(gateUnlockedOrderGuardRead(source,secondReady,firstDispatched));
        PaymentChannel limited=new PaymentChannel() {
            public Eligibility eligibility(String tenant,String order){if("first-funds".equals(Thread.currentThread().getName()))await(secondReady);return new Eligibility(order,"CNY","DELIVERED",new BigDecimal("30"),new BigDecimal("30"),"current",Instant.now());}
            public Result submit(String id,Map<String,Object> approved){calls.incrementAndGet();if("first-funds".equals(Thread.currentThread().getName()))firstDispatched.countDown();else secondReady.countDown();return new Result("UNKNOWN",null,"pending channel result");}
            public Result query(String id,Map<String,Object> approved){return new Result("UNKNOWN",null,"unknown");}
            public String name(){return "LIMITED";}public boolean synthetic(){return true;}
        };
        var ledger=new CompensationLedgerService(guardedStore,cases,limited,true,secret);
        Map<String,Object> secondRequest=Map.of("case_id",second.id(),"compensation_id","second","decision_id","second-decision","decision_version",1);
        FutureTask<Object> first=new FutureTask<>(()->submitOutcome(ledger,request("first")));
        FutureTask<Object> other=new FutureTask<>(()->submitOutcome(ledger,secondRequest));
        new Thread(first,"first-funds").start();new Thread(other,"second-funds").start();
        List<Object> outcomes=List.of(first.get(5,TimeUnit.SECONDS),other.get(5,TimeUnit.SECONDS));
        assertEquals(1,outcomes.stream().filter(PlatformStore.Entity.class::isInstance).count());
        assertEquals(1,outcomes.stream().filter(result->result instanceof PlatformException failure&&failure.status()==409).count());
        assertEquals(1,calls.get());assertEquals(1,store.list("test","compensation").size());
        assertEquals(0,new BigDecimal(store.require("test","compensation_order",guardId).data().get("reserved_total").toString()).compareTo(new BigDecimal("20")));
    }

    private Object submitOutcome(CompensationLedgerService ledger,Map<String,Object> request) {
        try{return ledger.submit(actor,request);}catch(PlatformException rejected){return rejected;}
    }

    private static void await(CountDownLatch latch) {
        try{assertTrue(latch.await(5,TimeUnit.SECONDS),"Concurrent operation did not reach its barrier");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }

    private static DataSource gateUnlockedOrderGuardRead(DataSource source,CountDownLatch secondReady,CountDownLatch firstDispatched) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException{return wrap(source.getConnection());}
            @Override public Connection getConnection(String user,String password) throws SQLException{return wrap(source.getConnection(user,password));}
            private Connection wrap(Connection connection) {
                AtomicBoolean orderGuardLocked=new AtomicBoolean();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    try {
                        Object result=method.invoke(connection,args);
                        if(!"prepareStatement".equals(method.getName())||!(result instanceof PreparedStatement statement)||!(args[0] instanceof String sql))return result;
                        Map<Integer,Object> parameters=new HashMap<>();
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(prepared,operation,arguments)->{
                            if("setString".equals(operation.getName()))parameters.put((Integer)arguments[0],arguments[1]);
                            if("executeQuery".equals(operation.getName())&&sql.contains("FROM platform_records")&&"compensation_order".equals(parameters.get(2))) {
                                if(sql.contains("FOR UPDATE"))orderGuardLocked.set(true);
                                else if(!orderGuardLocked.get()&&"second-funds".equals(Thread.currentThread().getName())) {
                                    secondReady.countDown();await(firstDispatched);
                                }
                            }
                            try{return operation.invoke(statement,arguments);}catch(InvocationTargetException failed){throw failed.getCause();}
                        });
                    }catch(InvocationTargetException failed){throw failed.getCause();}
                });
            }
        };
    }
}

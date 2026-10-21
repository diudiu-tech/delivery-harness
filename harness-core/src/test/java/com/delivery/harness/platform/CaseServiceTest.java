package com.delivery.harness.platform;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.store.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CaseServiceTest {
    private PlatformStore store;private CaseService cases;
    private final Principal agent=new Principal("t","agent",Set.of("AGENT"),Set.of("*"),Set.of("*"));
    private final Principal reviewer=new Principal("t","reviewer",Set.of("REVIEWER"),Set.of("*"),Set.of("*"));
    @BeforeEach void setup(){store=PlatformTestDatabase.memory();cases=new CaseService(store,new OrderSource("demo","","",""));org.springframework.test.util.ReflectionTestUtils.setField(cases,"directory",new com.delivery.harness.platform.auth.PrincipalDirectory("[{\"tenant_id\":\"t\",\"user_id\":\"reviewer-2\",\"roles\":[\"REVIEWER\"],\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]}]","pilot"));}
    @Test void approvalBindsImmutableDecisionAndNeverPays(){
        var c=cases.create(agent,Map.of("order_id","ORD-MERCHANT-SLOW","complaint_type","OVERTIME"));var review=prepareDecision(c.id());
        var claimed=cases.claim(reviewer,review.id(),review.version());
        var current=cases.require(reviewer,c.id());
        var action=cases.act(reviewer,review.id(),Map.of("expected_version",claimed.version(),"action","APPROVE","reason","confirmed evidence","decision_id","decision-1","decision_version",1,"evidence_snapshot_id",current.data().get("evidence_snapshot_id")));
        assertEquals("NOT_EXECUTED",action.data().get("payment_status"));assertEquals("APPROVED",cases.require(reviewer,c.id()).data().get("status"));
        assertEquals("NOT_EXECUTED",cases.require(reviewer,c.id()).data().get("payment_status"));
    }
    @Test void addedEvidenceInvalidatesApprovalAndOldReviewCannotSubmit(){
        var c=cases.create(agent,Map.of("order_id","ORD-MERCHANT-SLOW","complaint_type","OVERTIME"));var review=prepareDecision(c.id());var claimed=cases.claim(reviewer,review.id(),1);
        String previousSnapshot=cases.require(reviewer,c.id()).data().get("evidence_snapshot_id").toString();
        cases.addEvidence(agent,c.id(),Map.of("file_hash","a".repeat(64),"mime_type","image/png","size_bytes",20,"reference_uri","https://evidence.example/item"));
        assertNotEquals(previousSnapshot,cases.require(reviewer,c.id()).data().get("evidence_snapshot_id"));assertEquals("SUPERSEDED",store.require("t","review",review.id()).data().get("status"));
        assertThrows(PlatformException.class,() -> cases.act(reviewer,review.id(),Map.of("expected_version",claimed.version(),"action","APPROVE","reason","stale","decision_id","decision-1","decision_version",1,"evidence_snapshot_id",previousSnapshot)));
    }
    @Test void damageMaterialNeedsIndependentVerification(){
        var c=cases.create(agent,Map.of("order_id","ORD-DAMAGE","complaint_type","DAMAGED"));var m=cases.addEvidence(agent,c.id(),inlineMaterial("FULL"));
        assertFalse(cases.verifiedDamage(agent,c.id(),"FULL"));
        assertThrows(PlatformException.class,() -> cases.reviewMaterial(new Principal("t","agent",Set.of("REVIEWER"),Set.of("*"),Set.of("*")),m.id(),Map.of("expected_version",1,"action","VERIFY","reason","self")));
        cases.reviewMaterial(reviewer,m.id(),Map.of("expected_version",1,"action","VERIFY","reason","manually inspected original"));assertTrue(cases.verifiedDamage(agent,c.id(),"FULL"));
    }
    @Test void approvalAuditStorageFailureRollsBackAndCanBeRetried() throws Exception {
        var c=cases.create(agent,Map.of("order_id","ORDER-AUDIT","complaint_type","OVERTIME"));var review=prepareDecision(c.id());var claimed=cases.claim(reviewer,review.id(),1);var current=cases.require(reviewer,c.id());
        Map<String,Object> action=Map.of("expected_version",claimed.version(),"action","APPROVE","reason","confirmed","decision_id","decision-1","decision_version",1,"evidence_snapshot_id",current.data().get("evidence_snapshot_id"));
        var source=(javax.sql.DataSource)org.springframework.test.util.ReflectionTestUtils.getField(store,"source");try(var connection=source.getConnection();var command=connection.createStatement()){command.execute("DROP TABLE platform_audit_events");}
        assertEquals(503,assertThrows(PlatformException.class,() -> cases.act(reviewer,review.id(),action)).status());assertEquals("IN_REVIEW",store.require("t","review",review.id()).data().get("status"));assertEquals("IN_REVIEW",cases.require(reviewer,c.id()).data().get("status"));assertTrue(store.list("t","review_action").isEmpty());
        java.nio.file.Path migration=java.nio.file.Path.of("../harness-api/src/main/resources/db/migration/V1__platform_records.sql");if(!java.nio.file.Files.exists(migration))migration=java.nio.file.Path.of("harness-api/src/main/resources/db/migration/V1__platform_records.sql");
        try(var connection=source.getConnection();var command=connection.createStatement()){for(String sql:java.nio.file.Files.readString(migration).split(";"))if(sql.contains("platform_audit_events"))command.execute(sql);}
        cases.act(reviewer,review.id(),action);assertEquals("APPROVED",cases.require(reviewer,c.id()).data().get("status"));assertFalse(store.audits("t").isEmpty());
    }
    @Test void externalUrlsCannotBecomeVerifiedDamageWithoutFrozenContent(){var c=cases.create(agent,Map.of("order_id","ORD-DAMAGE","complaint_type","DAMAGED"));var m=cases.addEvidence(agent,c.id(),Map.of("file_hash","b".repeat(64),"mime_type","image/jpeg","size_bytes",20,"reference_uri","https://evidence.example/item","damage_level","FULL"));assertThrows(PlatformException.class,() -> cases.reviewMaterial(reviewer,m.id(),Map.of("expected_version",1,"action","VERIFY","reason","clicked checkbox")));assertFalse(cases.verifiedDamage(agent,c.id(),"FULL"));}
    @Test void nonexistentOrWrongScopeReassignmentCannotOrphanTheReview(){var c=cases.create(agent,Map.of("order_id","ORDER-OWNER","complaint_type","OVERTIME"));var review=prepareDecision(c.id());var claimed=cases.claim(reviewer,review.id(),1);var current=cases.require(reviewer,c.id());Map<String,Object> action=new LinkedHashMap<>(Map.of("expected_version",claimed.version(),"action","REASSIGN","reason","handover","decision_id","decision-1","decision_version",1,"evidence_snapshot_id",current.data().get("evidence_snapshot_id"),"target_user_id","nonexistent"));assertEquals("REVIEWER_NOT_FOUND",assertThrows(PlatformException.class,() -> cases.act(reviewer,review.id(),action)).code());assertEquals("reviewer",store.require("t","review",review.id()).data().get("owner"));assertEquals(claimed.version(),store.require("t","review",review.id()).version());}
    @Test void fundsRoleCanReadScopedCasesButCannotModifyEvidence(){var c=cases.create(agent,Map.of("order_id","ORDER-FUNDS","complaint_type","OVERTIME"));var funds=new Principal("t","finance",Set.of("FUNDS"),Set.of("*"),Set.of("*"));assertEquals(c.id(),cases.require(funds,c.id()).id());assertThrows(PlatformException.class,() -> cases.addEvidence(funds,c.id(),inlineMaterial("FULL")));}
    @Test void claimAndReassignmentSynchronizeCaseOwner(){var c=cases.create(agent,Map.of("order_id","ORDER-OWNER","complaint_type","OVERTIME"));var review=prepareDecision(c.id());var claimed=cases.claim(reviewer,review.id(),1);assertEquals("reviewer",cases.require(reviewer,c.id()).data().get("assignee"));var current=cases.require(reviewer,c.id());cases.act(reviewer,review.id(),Map.of("expected_version",claimed.version(),"action","REASSIGN","reason","shift handover","decision_id","decision-1","decision_version",1,"evidence_snapshot_id",current.data().get("evidence_snapshot_id"),"target_user_id","reviewer-2"));assertEquals("reviewer-2",cases.require(reviewer,c.id()).data().get("assignee"));}
    @Test void activeOrCompletedPaymentsLockEvidenceAndClosure(){
        for(String status:List.of("PENDING","SUBMITTED","UNKNOWN","SUCCEEDED")){
            setup();var c=cases.create(agent,Map.of("order_id","ORDER-FUNDS","complaint_type","DAMAGED"));var material=cases.addEvidence(agent,c.id(),inlineMaterial("FULL"));var latest=cases.require(agent,c.id());Map<String,Object> data=new LinkedHashMap<>(latest.data());data.put("payment_status",status);data.put("status","APPROVED");store.update("t","case",c.id(),latest.version(),data,"funds","PAYMENT");
            assertThrows(PlatformException.class,() -> cases.addEvidence(agent,c.id(),inlineMaterial("FULL")));
            assertThrows(PlatformException.class,() -> cases.reviewMaterial(reviewer,material.id(),Map.of("expected_version",1,"action","VERIFY","reason","inspected")));
            assertThrows(PlatformException.class,() -> cases.close(agent,c.id(),Map.of("expected_version",cases.require(agent,c.id()).version(),"reason","close")));
            assertEquals(status,cases.require(agent,c.id()).data().get("payment_status"));assertEquals(1,store.list("t","material").size());
        }
    }
    public static Map<String,Object> inlineMaterial(String level){
        byte[] content="manually inspected damage evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash;
        try{hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));}catch(Exception e){throw new IllegalStateException(e);}
        return Map.of("file_hash",hash,"mime_type","text/plain","size_bytes",content.length,"content_base64",Base64.getEncoder().encodeToString(content),"damage_level",level);
    }
    @Test void scopesAndTenantAreServerEnforced(){var c=cases.create(agent,Map.of("order_id","ORD-DAMAGE","complaint_type","DAMAGED"));
        assertThrows(PlatformException.class,() -> cases.require(new Principal("different","other",Set.of("AGENT"),Set.of("*"),Set.of("*")),c.id()));
        assertThrows(PlatformException.class,() -> cases.require(new Principal("t","scoped",Set.of("AGENT"),Set.of("denied-city"),Set.of("*")),c.id()));}
    private PlatformStore.Entity prepareDecision(String caseId){var c=store.require("t","case",caseId);String snapshot=c.data().get("evidence_snapshot_id").toString();
        store.create("t","decision","decision-1",Map.of("case_id",caseId,"evidence_snapshot_id",snapshot,"decision_status","DETERMINED","amount",5),"worker","DECISION");
        var review=store.create("t","review","review-1",Map.of("case_id",caseId,"decision_id","decision-1","evidence_snapshot_id",snapshot,"status","PENDING"),"worker","REVIEW");
        Map<String,Object> data=new LinkedHashMap<>(c.data());data.put("current_decision_id","decision-1");data.put("review_id",review.id());data.put("status","PENDING_REVIEW");store.update("t","case",caseId,c.version(),data,"worker","READY");return review;}
}

package com.delivery.harness.platform.operations;

import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.store.PlatformException;
import com.delivery.harness.platform.store.PlatformStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** Reviewed, reference-aware retention; automatic deletion is deliberately disabled. */
@Service
public class RetentionService {
    private static final List<String> CASE_KINDS = List.of("case", "material", "order_snapshot", "decision", "review", "review_action", "execution", "feedback", "retention_hold");
    private final PlatformStore store;
    private final CaseService cases;
    private final boolean enabled;
    private final long days;
    public RetentionService(PlatformStore store, CaseService cases,
            @Value("${harness.retention.enabled:false}") boolean enabled,
            @Value("${harness.retention.case-days:180}") long days) {
        if (days < 1 || days > 3650) throw new IllegalArgumentException("Retention must be 1..3650 days");
        this.store=store; this.cases=cases; this.enabled=enabled; this.days=days;
    }
    public PlatformStore.Entity hold(Principal actor,String caseId,Map<String,Object> input) {
        actor.requireRole("AUDITOR","ADMIN");cases.require(actor,caseId);
        String reason=CaseService.text(input,"reason");
        if (!(input.get("locked") instanceof Boolean)) throw PlatformException.invalid("locked must be a boolean");
        return store.transact(tx -> {
            var c=tx.lock(actor.tenantId(),"case",caseId);CaseService.authorize(actor,c);
            Map<String,Object> caseData=new LinkedHashMap<>(c.data());caseData.put("retention_locked",input.get("locked"));
            tx.update(actor.tenantId(),"case",caseId,c.version(),caseData,actor.userId(),"CASE_RETENTION_HOLD_CHANGED");
            Map<String,Object> data=Map.of("case_id",caseId,"locked",input.get("locked"),"reason",reason,"actor",actor.userId());
            Optional<PlatformStore.Entity> old=tx.get(actor.tenantId(),"retention_hold",caseId);
            if(old.isPresent())return tx.update(actor.tenantId(),"retention_hold",caseId,CaseService.number(input,"expected_version"),data,actor.userId(),"RETENTION_HOLD_CHANGED");
            return tx.create(actor.tenantId(),"retention_hold",caseId,data,actor.userId(),"RETENTION_HOLD_CHANGED");
        });
    }
    public PlatformStore.Entity plan(Principal actor,Map<String,Object> input) {
        actor.requireRole("ADMIN");String caseId=CaseService.text(input,"case_id"),reason=CaseService.text(input,"reason");
        cases.require(actor,caseId);
        return store.transact(tx -> {
            List<Map<String,Object>> records=graph(tx,actor,caseId);
            List<String> blocked=blockers(tx,actor,caseId);
            Map<String,Object> data=new LinkedHashMap<>();data.put("case_id",caseId);data.put("created_by",actor.userId());data.put("reason",reason);
            data.put("case_retention_days",days);data.put("records",records);data.put("graph_hash",PlatformStore.hash(records));
            data.put("blocked_reasons",blocked);data.put("status",blocked.isEmpty()?"PENDING_APPROVAL":"BLOCKED");data.put("execution_enabled",enabled);
            return tx.create(actor.tenantId(),"retention_plan",UUID.randomUUID().toString(),data,actor.userId(),"RETENTION_PLANNED");
        });
    }
    public PlatformStore.Entity get(Principal actor,String id) {
        actor.requireRole("ADMIN","AUDITOR");var plan=store.require(actor.tenantId(),"retention_plan",id);
        if(!"COMPLETED".equals(plan.data().get("status")))cases.require(actor,CaseService.text(plan.data(),"case_id"));
        else actor.authorizeScope(CaseService.string(plan.data(),"city_code"),CaseService.string(plan.data(),"station_id"));
        return plan;
    }
    public PlatformStore.Entity approve(Principal actor,String id,Map<String,Object> input) {
        actor.requireRole("ADMIN");if(!enabled)throw new PlatformException(409,"RETENTION_DISABLED","Retention execution is disabled; the plan is a dry run");
        get(actor,id);String reason=CaseService.text(input,"reason");
        return store.transact(tx -> {
            var plan=tx.require(actor.tenantId(),"retention_plan",id);String caseId=CaseService.text(plan.data(),"case_id");
            if(!"PENDING_APPROVAL".equals(plan.data().get("status")))throw PlatformException.conflict("Plan cannot be executed in its current state");
            if(actor.userId().equals(plan.data().get("created_by")))throw new PlatformException(403,"SEPARATION_OF_DUTIES","A different administrator must approve deletion");
            var c=tx.lock(actor.tenantId(),"case",caseId);CaseService.authorize(actor,c);
            List<String> blocked=blockers(tx,actor,caseId);if(!blocked.isEmpty())throw PlatformException.conflict(String.join("; ",blocked));
            List<Map<String,Object>> current=graph(tx,actor,caseId);
            if(!Objects.equals(plan.data().get("graph_hash"),input.get("graph_hash"))||!Objects.equals(plan.data().get("graph_hash"),PlatformStore.hash(current)))throw PlatformException.conflict("References changed; create a new retention plan");
            // Every referenced row is version-checked, and deletion/audit/receipt commit together.
            for(Map<String,Object> item:current)tx.delete(actor.tenantId(),item.get("kind").toString(),item.get("id").toString(),((Number)item.get("version")).longValue(),actor.userId(),reason);
            Map<String,Object> receipt=new LinkedHashMap<>(plan.data());receipt.put("status","COMPLETED");receipt.put("approved_by",actor.userId());receipt.put("approved_reason",reason);
            receipt.put("deleted_at",Instant.now().toString());receipt.put("deleted_count",current.size());receipt.put("city_code",c.data().get("city_code"));receipt.put("station_id",c.data().get("station_id"));
            return tx.update(actor.tenantId(),"retention_plan",id,CaseService.number(input,"expected_version"),receipt,actor.userId(),"RETENTION_COMPLETED");
        });
    }
    private List<Map<String,Object>> graph(PlatformStore.Tx tx,Principal actor,String caseId) {
        var c=tx.require(actor.tenantId(),"case",caseId);CaseService.authorize(actor,c);
        List<Map<String,Object>> graph=new ArrayList<>();Set<String> executionIds=new HashSet<>();
        for(String kind:CASE_KINDS)for(var record:tx.list(actor.tenantId(),kind))if(("case".equals(kind)&&caseId.equals(record.id()))||caseId.equals(record.data().get("case_id"))) {
            graph.add(Map.of("kind",kind,"id",record.id(),"version",record.version(),"hash",PlatformStore.hash(record.data())));
            if("execution".equals(kind))executionIds.add(record.id());
        }
        for(var idem:tx.list(actor.tenantId(),"idempotency"))if(executionIds.contains(idem.data().get("execution_id")))graph.add(Map.of("kind","idempotency","id",idem.id(),"version",idem.version(),"hash",PlatformStore.hash(idem.data())));
        if(graph.size()>1000)throw new PlatformException(409,"RETENTION_PLAN_TOO_LARGE","Case retention graph exceeds 1000 records; use an offline reviewed migration");
        graph.sort(Comparator.comparing(item -> item.get("kind")+":"+item.get("id")));
        return List.copyOf(graph);
    }
    private List<String> blockers(PlatformStore.Tx tx,Principal actor,String caseId) {
        var c=tx.require(actor.tenantId(),"case",caseId);CaseService.authorize(actor,c);List<String> reasons=new ArrayList<>();
        if(!"CLOSED".equals(c.data().get("status")))reasons.add("Case is not closed");
        Object closed=c.data().get("closed_at");if(closed==null||Instant.parse(closed.toString()).isAfter(Instant.now().minusSeconds(days*86400)))reasons.add("Case retention period has not elapsed");
        if(tx.get(actor.tenantId(),"retention_hold",caseId).map(h -> Boolean.TRUE.equals(h.data().get("locked"))).orElse(false))reasons.add("Case has a retention hold");
        if(tx.list(actor.tenantId(),"compensation").stream().anyMatch(e -> caseId.equals(e.data().get("case_id"))))reasons.add("Financial ledger requires retained approval references");
        String orderId=CaseService.text(c.data(),"order_id");
        for(String kind:List.of("eval_dataset","eval_run","eval_result"))if(tx.list(actor.tenantId(),kind).stream().anyMatch(e -> contains(e.data(),orderId)||contains(e.data(),caseId))) {
            reasons.add("Evaluation snapshots retain this evidence; retire them through a reviewed dataset migration first");break;
        }
        return List.copyOf(reasons);
    }
    private static boolean contains(Object value,String id) {
        if(value instanceof Map<?,?> map)return map.values().stream().anyMatch(v -> contains(v,id));
        if(value instanceof Collection<?> values)return values.stream().anyMatch(v -> contains(v,id));
        return Objects.equals(value,id);
    }
}

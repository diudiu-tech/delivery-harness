package com.delivery.harness.platform.operations;

import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.auth.Redaction;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.store.PlatformStore;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class OperationsService {
    private final PlatformStore store;
    private final CaseService cases;
    public OperationsService(PlatformStore store, CaseService cases) { this.store=store; this.cases=cases; }

    public Map<String,Object> metrics(Principal actor) {
        actor.requireRole("OPERATOR","QUALITY","AUDITOR","ADMIN");
        Set<String> visible = new HashSet<>();
        store.list(actor.tenantId(),"case").stream().filter(c -> actor.canAccess(CaseService.string(c.data(),"city_code"),CaseService.string(c.data(),"station_id"))).forEach(c -> visible.add(c.id()));
        List<PlatformStore.Entity> executions = store.list(actor.tenantId(),"execution").stream().filter(e -> visible.contains(e.data().get("case_id"))).toList();
        Map<String,Object> summary = new LinkedHashMap<>(); summary.put("案件总数",visible.size()); summary.put("执行总数",executions.size());
        for (String status:List.of("SUCCESS","DEGRADED","FAILED","TIMED_OUT","CANCELLED","QUEUED","RUNNING")) summary.put(status,executions.stream().filter(e -> status.equals(e.data().get("status"))).count());
        List<Long> durations = executions.stream().map(e -> duration(e.data())).filter(Objects::nonNull).sorted().toList();
        Map<String,Object> latency = new LinkedHashMap<>();
        latency.put("count",durations.size()); latency.put("p50_ms",percentile(durations,.5)); latency.put("p95_ms",percentile(durations,.95)); latency.put("p99_ms",percentile(durations,.99));
        Map<String,Object> result = new LinkedHashMap<>();result.put("summary",summary);result.put("latency",latency);
        result.put("quality_note","Execution completion is separate from decision accuracy; unmeasured model use and cost are unknown");
        Map<String,java.math.BigDecimal> costs=new TreeMap<>();long known=0,unknown=0;
        for(PlatformStore.Entity execution:executions) {
            Object value=execution.data().get("model_call_metadata");
            if(value instanceof Map<?,?> metadata && "KNOWN".equals(metadata.get("cost_status")) && metadata.get("estimated_cost")!=null) {
                String group=metadata.get("cost_currency")+":"+metadata.get("price_version");
                costs.merge(group,new java.math.BigDecimal(metadata.get("estimated_cost").toString()),java.math.BigDecimal::add);known++;
            } else unknown++;
        }
        Map<String,Object> cost=new LinkedHashMap<>();cost.put("known_executions",known);cost.put("unknown_executions",unknown);
        cost.put("status",unknown==0&&known>0?"KNOWN":known>0?"PARTIALLY_KNOWN":"UNKNOWN");
        cost.put("totals_by_currency_and_price_version",costs);result.put("model_cost",cost);
        return result;
    }

    public List<Map<String,Object>> audit(Principal actor,int limit,String cursor) {
        actor.requireRole("AUDITOR","ADMIN");
        int bounded=Math.max(1,Math.min(100,limit));
        Set<String> accessible = new HashSet<>();
        store.list(actor.tenantId(),"case").stream().filter(c -> actor.canAccess(CaseService.string(c.data(),"city_code"),CaseService.string(c.data(),"station_id"))).forEach(c -> accessible.add(c.id()));
        for(String kind:List.of("decision","review","review_action","execution","material","order_snapshot","compensation","feedback")) {
            store.list(actor.tenantId(),kind).stream().filter(e -> accessible.contains(e.data().get("case_id"))).forEach(e -> accessible.add(e.id()));
        }
        for(PlatformStore.Entity plan:store.list(actor.tenantId(),"retention_plan")) {
            if("COMPLETED".equals(plan.data().get("status")) && actor.canAccess(CaseService.string(plan.data(),"city_code"),CaseService.string(plan.data(),"station_id"))) {
                accessible.add(plan.id());accessible.add(String.valueOf(plan.data().get("case_id")));
                if(plan.data().get("records") instanceof List<?> records)for(Object record:records)if(record instanceof Map<?,?> data)accessible.add(String.valueOf(data.get("id")));
            }
        }
        boolean unrestricted = actor.cityCodes().contains("*") && actor.stationIds().contains("*");
        return store.audits(actor.tenantId()).stream().filter(e -> unrestricted||accessible.contains(e.get("target")))
                .filter(e -> cursor==null||String.valueOf(e.get("created_at")).compareTo(cursor)<0)
                .sorted(Comparator.comparing(e -> String.valueOf(e.get("created_at")),Comparator.reverseOrder())).limit(bounded).toList();
    }

    public Map<String,Object> exportCase(Principal actor,String id) {
        actor.requireRole("OPERATOR","SUPERVISOR","AUDITOR");
        Map<String,Object> detail=cases.detail(actor,id);
        store.transact(tx -> {tx.audit(actor.tenantId(),actor.userId(),"CASE_EXPORTED",id,Map.of("format","structured_json","version",detail.get("version")));return null;});
        return Redaction.map(detail);
    }
    private static Long duration(Map<String,Object> data) {
        Object value=data.get("duration_ms");if(value==null)value=data.get("total_duration_ms");
        if(value instanceof Number n)return Math.max(0L,n.longValue());
        if(data.get("started_at")!=null && data.get("finished_at")!=null) {
            try{return Math.max(0L,java.time.Duration.between(java.time.Instant.parse(data.get("started_at").toString()),java.time.Instant.parse(data.get("finished_at").toString())).toMillis());}catch(RuntimeException ignored){return null;}
        }
        return null;
    }
    static Long percentile(List<Long> values,double percentile) {
        if(values.isEmpty())return null;
        return values.get(Math.min(values.size()-1,Math.max(0,(int)Math.ceil(percentile*values.size())-1)));
    }
}

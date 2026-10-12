package com.delivery.harness.platform.operations;

import com.delivery.harness.platform.PlatformTestDatabase;
import com.delivery.harness.platform.adapter.OrderSource;
import com.delivery.harness.platform.auth.AuthContext.Principal;
import com.delivery.harness.platform.casework.CaseService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OperationsServiceTest {
    @Test void knownCostAndUnknownSamplesStaySeparateAndFeedbackIsScopedInAudit() {
        var store=PlatformTestDatabase.memory();var cases=new CaseService(store,new OrderSource("demo","","",""));
        var actor=new Principal("test","operator",Set.of("AGENT","OPERATOR","AUDITOR"),Set.of("*"),Set.of("*"));
        var c=cases.create(actor,Map.of("order_id","DEMO-002","complaint_type","OVERTIME"));
        store.create("test","execution","known",Map.of("case_id",c.id(),"status","SUCCESS","duration_ms",100,"model_call_metadata",Map.of("cost_status","KNOWN","cost_currency","CNY","price_version","v1","estimated_cost","0.012")),"worker","EXECUTION");
        store.create("test","execution","unknown",Map.of("case_id",c.id(),"status","DEGRADED","duration_ms",300),"worker","EXECUTION");
        store.create("test","feedback","correction",Map.of("case_id",c.id()),"operator","FEEDBACK_SUBMITTED");
        var scoped=new Principal("test","auditor",Set.of("AUDITOR","OPERATOR"),Set.of(c.data().get("city_code").toString()),Set.of(c.data().get("station_id").toString()));
        var service=new OperationsService(store,cases);var metrics=service.metrics(scoped);var cost=(Map<?,?>)metrics.get("model_cost");
        assertEquals("PARTIALLY_KNOWN",cost.get("status"));assertEquals(1L,cost.get("known_executions"));assertEquals(1L,cost.get("unknown_executions"));
        assertEquals(new java.math.BigDecimal("0.012"),((Map<?,?>)cost.get("totals_by_currency_and_price_version")).get("CNY:v1"));
        assertEquals(300L,((Map<?,?>)metrics.get("latency")).get("p95_ms"));
        assertTrue(service.audit(scoped,100,null).stream().anyMatch(e -> "correction".equals(e.get("target"))));
    }
}

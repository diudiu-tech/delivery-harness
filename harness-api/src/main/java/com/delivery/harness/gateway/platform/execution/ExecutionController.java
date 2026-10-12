package com.delivery.harness.gateway.platform.execution;
import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.execution.ExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/executions")
public class ExecutionController {
    private final ExecutionService executions;
    private final com.delivery.harness.platform.store.PlatformStore store;
    public ExecutionController(ExecutionService executions,com.delivery.harness.platform.store.PlatformStore store){this.executions=executions;this.store=store;}
    @PostMapping public ResponseEntity<?> submit(@RequestBody Map<String,Object> input,@RequestHeader(value="Idempotency-Key",required=false) String key,HttpServletRequest request){return ResponseEntity.accepted().body(ExecutionService.publicView(executions.submit(PrincipalAccess.get(request),input,key)));}
    @GetMapping public Map<String,Object> list(@RequestParam(defaultValue="") String cursor,@RequestParam(defaultValue="50") int limit,HttpServletRequest request){
        var actor=PrincipalAccess.get(request);actor.requireRole("AGENT","OPERATOR","REVIEWER","SUPERVISOR","QUALITY","AUDITOR");var page=store.page(actor.tenantId(),"execution",cursor,limit);
        var items=page.stream().filter(e -> actor.canAccess(com.delivery.harness.platform.casework.CaseService.string(e.data(),"city_code"),com.delivery.harness.platform.casework.CaseService.string(e.data(),"station_id"))).map(ExecutionService::publicView).toList();
        return Map.of("items",items,"cursor",page.size()==limit?page.get(page.size()-1).id():"");
    }
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable String id,HttpServletRequest request){return ExecutionService.publicView(executions.require(PrincipalAccess.get(request),id));}
    @PostMapping("/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id,HttpServletRequest request){return ExecutionService.publicView(executions.cancel(PrincipalAccess.get(request),id));}
    @GetMapping("/queue-status") public Map<String,Object> queue(HttpServletRequest request){PrincipalAccess.get(request).requireRole("ADMIN","QUALITY");return executions.queueStatus();}
}

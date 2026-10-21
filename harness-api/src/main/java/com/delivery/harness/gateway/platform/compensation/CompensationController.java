package com.delivery.harness.gateway.platform.compensation;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.compensation.CompensationLedgerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/compensations")
public class CompensationController {
    private final CompensationLedgerService ledger;
    public CompensationController(CompensationLedgerService ledger) { this.ledger = ledger; }
    @PostMapping public Object submit(HttpServletRequest request, @RequestBody Map<String,Object> body) {
        return CaseService.view(ledger.submit(PrincipalAccess.get(request), body));
    }
    @GetMapping("/{id}") public Object get(HttpServletRequest request, @PathVariable String id) {
        return CaseService.view(ledger.get(PrincipalAccess.get(request), id));
    }
    @PostMapping("/{id}/reconcile") public Object reconcile(HttpServletRequest request, @PathVariable String id) {
        return CaseService.view(ledger.reconcile(PrincipalAccess.get(request), id));
    }
    @PostMapping("/{id}/callback") public Object callback(HttpServletRequest request, @PathVariable String id,
            @RequestHeader("X-Event-Id") String event, @RequestHeader("X-Callback-Timestamp") String timestamp,
            @RequestHeader("X-Callback-Signature") String signature, @RequestBody Map<String,Object> body) {
        return CaseService.view(ledger.callback(PrincipalAccess.get(request), id, event, timestamp, signature, body));
    }
}

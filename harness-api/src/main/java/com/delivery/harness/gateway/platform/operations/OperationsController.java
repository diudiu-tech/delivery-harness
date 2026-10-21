package com.delivery.harness.gateway.platform.operations;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.operations.OperationsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2")
public class OperationsController {
    private final OperationsService service;
    public OperationsController(OperationsService service) {this.service=service;}
    @GetMapping("/metrics") public Object metrics(HttpServletRequest request) {return service.metrics(PrincipalAccess.get(request));}
    @GetMapping("/audit-events") public Object audit(HttpServletRequest request,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {return service.audit(PrincipalAccess.get(request),limit,cursor);}
    @GetMapping("/cases/{id}/export") public Object export(HttpServletRequest request,@PathVariable String id) {return service.exportCase(PrincipalAccess.get(request),id);}
}

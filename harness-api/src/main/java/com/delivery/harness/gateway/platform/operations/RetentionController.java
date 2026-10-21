package com.delivery.harness.gateway.platform.operations;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.operations.RetentionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
public class RetentionController {
    private final RetentionService retention;
    public RetentionController(RetentionService retention){this.retention=retention;}
    @PostMapping("/cases/{id}/retention-hold") public Object hold(HttpServletRequest request,@PathVariable String id,@RequestBody Map<String,Object> body){return retention.hold(PrincipalAccess.get(request),id,body);}
    @PostMapping("/retention/plans") public Object plan(HttpServletRequest request,@RequestBody Map<String,Object> body){return retention.plan(PrincipalAccess.get(request),body);}
    @GetMapping("/retention/plans/{id}") public Object get(HttpServletRequest request,@PathVariable String id){return retention.get(PrincipalAccess.get(request),id);}
    @PostMapping("/retention/plans/{id}/approve") public Object approve(HttpServletRequest request,@PathVariable String id,@RequestBody Map<String,Object> body){return retention.approve(PrincipalAccess.get(request),id,body);}
}

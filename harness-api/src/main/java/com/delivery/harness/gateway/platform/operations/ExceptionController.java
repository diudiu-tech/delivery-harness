package com.delivery.harness.gateway.platform.operations;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.casework.ManualExceptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/cases")
public class ExceptionController {
    private final ManualExceptionService exceptions;
    public ExceptionController(ManualExceptionService exceptions){this.exceptions=exceptions;}
    @PostMapping("/{id}/exceptions") public Object propose(HttpServletRequest request,@PathVariable String id,@RequestBody Map<String,Object> body){return CaseService.view(exceptions.propose(PrincipalAccess.get(request),id,body));}
}

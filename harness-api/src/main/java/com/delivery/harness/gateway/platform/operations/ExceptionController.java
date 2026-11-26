package com.delivery.harness.gateway.platform.operations;

import com.delivery.harness.gateway.platform.security.PrincipalAccess;
import com.delivery.harness.platform.casework.CaseService;
import com.delivery.harness.platform.casework.ManualExceptionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/cases")
public class ExceptionController {
    private final ManualExceptionService exceptions;

    public ExceptionController(ManualExceptionService exceptions) {
        this.exceptions = exceptions;
    }

    @PostMapping("/{id}/exceptions")
    public Object propose(
            HttpServletRequest request,
            @PathVariable String id,
            @Valid @RequestBody ExceptionRequest body) {
        return CaseService.view(exceptions.propose(PrincipalAccess.get(request), id, body.toMap()));
    }
}

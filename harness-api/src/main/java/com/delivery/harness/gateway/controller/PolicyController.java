package com.delivery.harness.gateway.controller;

import com.delivery.harness.application.CaseApplicationService;
import com.delivery.harness.common.dto.HarnessResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final CaseApplicationService caseApplicationService;

    @GetMapping("/active")
    public HarnessResponse<CaseApplicationService.PolicySummaryView> getActivePolicy() {
        return HarnessResponse.success(caseApplicationService.getActivePolicy());
    }
}

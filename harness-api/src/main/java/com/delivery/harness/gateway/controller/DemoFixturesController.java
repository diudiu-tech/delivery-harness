package com.delivery.harness.gateway.controller;

import com.delivery.harness.application.CaseApplicationService;
import com.delivery.harness.common.dto.HarnessResponse;
import com.delivery.harness.evidence.EvidenceSource;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes synthetic contract fixtures only when the {@code demo} profile is
 * explicitly activated.
 */
@Profile("demo")
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoFixturesController {

    private final CaseApplicationService caseApplicationService;

    @GetMapping("/fixtures")
    public HarnessResponse<List<EvidenceSource.FixtureSummary>> listFixtures() {
        return HarnessResponse.success(caseApplicationService.listDemoFixtures());
    }
}

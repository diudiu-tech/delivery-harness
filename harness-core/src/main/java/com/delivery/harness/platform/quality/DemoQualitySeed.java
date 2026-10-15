package com.delivery.harness.platform.quality;

import com.delivery.harness.platform.auth.PrincipalDirectory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Demo initialization is an explicit startup mutation, never a side effect of GET or runtime
 * resolution.
 */
@Component
@ConditionalOnProperty(name = "harness.mode", havingValue = "demo", matchIfMissing = true)
public class DemoQualitySeed {
    private final QualityEvalService evaluation;

    public DemoQualitySeed(QualityEvalService evaluation) {
        this.evaluation = evaluation;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void seed() {
        evaluation.ensureDefaults(PrincipalDirectory.demoPrincipals().get("demo-agent"));
    }
}

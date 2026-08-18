package com.delivery.harness.gateway.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the HTTP model transport with {@link StubLlmClient}.
 *
 * <p>Import this on any test that exercises a workflow end to end. Tests must
 * never require a running model: the suite has to be runnable in CI, offline,
 * with no Ollama and no Docker daemon.
 */
@TestConfiguration
public class StubLlmClientConfig {

    @Bean
    @Primary
    public StubLlmClient stubLlmClient() {
        return new StubLlmClient();
    }
}

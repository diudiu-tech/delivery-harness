package com.delivery.harness.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class DemoApiTestSupport {
    @Bean
    MockMvcBuilderCustomizer explicitDemoCredential(
            @Value("${harness.test.default-bearer:demo-agent-token}") String bearer) {
        return builder ->
                builder.defaultRequest(
                        get("/").header("Host", "localhost:8080")
                                .header("Authorization", "Bearer " + bearer));
    }
}

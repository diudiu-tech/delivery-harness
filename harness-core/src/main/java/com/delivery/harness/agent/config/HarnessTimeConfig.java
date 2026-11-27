package com.delivery.harness.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class HarnessTimeConfig {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock harnessClock() {
        return Clock.system(DEFAULT_ZONE);
    }
}

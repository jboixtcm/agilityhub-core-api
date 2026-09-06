package com.agilityhub.core.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestConfiguration {

    public static final Instant INITIAL_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    @Bean
    @Primary
    MockClock mockClock() {
        return new MockClock(INITIAL_INSTANT);
    }

    @Bean
    Fixtures fixtures(ObjectMapper objectMapper) {
        return new Fixtures(objectMapper);
    }
}

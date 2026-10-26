package com.agilityhub.core.shared.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class PrivateFileStorageConfiguration {
    @Bean PrivateFileStorageSettings privateFileStorageSettings(Environment environment) {
        return PrivateFileStorageSettings.from(environment);
    }
}

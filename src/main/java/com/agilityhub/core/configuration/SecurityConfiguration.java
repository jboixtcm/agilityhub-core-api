package com.agilityhub.core.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        http.authorizeHttpRequests(authorize -> {
            authorize.requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll();
            if (environment.acceptsProfiles(Profiles.of("local"))) {
                authorize.requestMatchers(HttpMethod.GET, "/v3/api-docs").permitAll();
            }
            authorize.anyRequest().denyAll();
        });
        return http.build();
    }
}

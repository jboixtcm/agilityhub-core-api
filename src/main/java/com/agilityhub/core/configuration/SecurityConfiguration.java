package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.api.ApiExceptionHandler;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping routes,
            ApiExceptionHandler errors, ObjectMapper mapper) throws Exception {
        http.authorizeHttpRequests(authorize -> {
            authorize.requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll();
            if (environment.acceptsProfiles(Profiles.of("local"))) {
                authorize.requestMatchers(HttpMethod.GET, "/v3/api-docs").permitAll();
            }
            // Only unmapped API routes reach MVC's standard NOT_FOUND advice. Known routes stay denied by default.
            authorize.requestMatchers(request -> {
                if (!request.getRequestURI().startsWith("/api/v1/")) { return false; }
                try { return routes.getHandler(request) == null; }
                catch (Exception methodOrMediaMismatch) { return false; }
            }).permitAll();
            authorize.anyRequest().denyAll();
        });
        http.exceptionHandling(errorsConfig -> errorsConfig
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    mapper.writeValue(response.getOutputStream(), errors.body(new ApiException(ErrorCode.FORBIDDEN), request));
                })
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    mapper.writeValue(response.getOutputStream(), errors.body(new ApiException(ErrorCode.FORBIDDEN), request));
                }));
        return http.build();
    }
}

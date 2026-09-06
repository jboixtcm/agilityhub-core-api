package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.api.ApiExceptionHandler;
import com.agilityhub.core.shared.api.TenantFilter;
import com.agilityhub.core.shared.api.RateLimitFilter;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean @org.springframework.core.annotation.Order(0)
    SecurityFilterChain managementSecurity(HttpSecurity http, Environment environment) throws Exception {
        http.securityMatcher(request -> request.getLocalPort() == environment.getProperty("local.management.port", Integer.class,
                        environment.getProperty("management.server.port", Integer.class, 8081)))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.HEAD, "/actuator/health").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment, JwtDecoder decoder,
            ObjectProvider<FilterRegistrationBean<TenantFilter>> tenants,
            FilterRegistrationBean<RateLimitFilter> rateLimits, org.springframework.web.cors.CorsConfigurationSource clubCors,
            ApiExceptionHandler errors, ObjectMapper mapper) throws Exception {
        SecurityBaselineConfiguration.headersAndCors(http, environment, clubCors);
        http.csrf(csrf -> csrf.disable()).sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable());
        http.authorizeHttpRequests(authorize -> {
            authorize.requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/branding", "/api/v1/manifest.webmanifest",
                    "/api/v1/public/**").permitAll();
            if (environment.acceptsProfiles(Profiles.of("local", "test"))
                    && !environment.acceptsProfiles(Profiles.of("staging", "prod"))) {
                authorize.requestMatchers(HttpMethod.GET, "/api/v1/openapi.json").permitAll();
            }
            authorize.requestMatchers("/actuator/**", "/v3/api-docs/**", "/api/v1/openapi.json", "/api/v1/openapi.json/**").denyAll();
            authorize.anyRequest().authenticated();
        });
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles"); authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter(); converter.setJwtGrantedAuthoritiesConverter(authorities);
        http.oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((request, response, exception) -> writeError(request, response, ErrorCode.UNAUTHENTICATED, errors, mapper))
                .accessDeniedHandler((request, response, exception) -> writeError(request, response, ErrorCode.FORBIDDEN, errors, mapper)));
        http.exceptionHandling(config -> config
                .authenticationEntryPoint((request, response, exception) -> writeError(request, response, ErrorCode.UNAUTHENTICATED, errors, mapper))
                .accessDeniedHandler((request, response, exception) -> writeError(request, response, ErrorCode.FORBIDDEN, errors, mapper)));
        var tenant = tenants.getIfAvailable();
        http.addFilterAfter(rateLimits.getFilter(), BearerTokenAuthenticationFilter.class);
        if (tenant != null) { http.addFilterAfter(tenant.getFilter(), RateLimitFilter.class); }
        return http.build();
    }
    static void writeError(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                           ErrorCode code, ApiExceptionHandler errors, ObjectMapper mapper) throws java.io.IOException {
        response.setStatus(code.httpStatus()); response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), errors.body(new ApiException(code), request));
    }
}

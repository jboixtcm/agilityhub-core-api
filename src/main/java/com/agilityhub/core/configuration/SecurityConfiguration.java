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
            ApiExceptionHandler errors, ObjectMapper mapper, com.agilityhub.core.shared.application.AccountAccess accountAccess,
            com.agilityhub.core.identity.application.ImpersonationService impersonations) throws Exception {
        SecurityBaselineConfiguration.headersAndCors(http, environment, clubCors);
        http.csrf(csrf -> csrf.disable()).sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable());
        http.authorizeHttpRequests(authorize -> {
            // Let MVC reject unsupported liveness methods with the public 405 error contract.
            authorize.requestMatchers("/api/v1/health").permitAll();
            authorize.requestMatchers(HttpMethod.GET, "/api/v1/branding", "/api/v1/manifest.webmanifest",
                    "/api/v1/signup", "/api/v1/signup/towns",
                    "/api/v1/public/**", "/api/v1/country-profile", "/api/v1/country-profile/postal-codes/*",
                    "/.well-known/openid-configuration", "/oauth2/authorize", "/connect/logout").permitAll();
            // S08 R-08-08: the .ics link authenticates with its signed token, not a JWT; the club comes from the host.
            authorize.requestMatchers(HttpMethod.GET, "/api/v1/bookings/*/calendar.ics").permitAll();
            // E5-T24 (CONVENCIONS_API §5, A31): a signed local file URL authorises itself; its service checks the signature.
            // E5-T26: PUT /api/v1/signup/uploads is one of them.
            authorize.requestMatchers(com.agilityhub.core.shared.api.SignedFileRequests::matches).permitAll();
            authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/magic-link", "/oauth2/token", "/webhooks/email/sendgrid",
                    "/api/v1/signup", "/api/v1/signup/identity-checks", "/api/v1/signup/upload-urls",
                    "/api/v1/signup/family-group-lookups", "/api/v1/checkout-sessions").permitAll();
            if (environment.acceptsProfiles(Profiles.of("local", "test"))
                    && !environment.acceptsProfiles(Profiles.of("staging", "prod"))) {
                authorize.requestMatchers(HttpMethod.GET, "/api/v1/openapi.json").permitAll();
                // S15 WP-15-E end-to-end clock; the controller itself only exists under these profiles.
                authorize.requestMatchers(HttpMethod.POST, "/api/v1/test/clock").permitAll();
            }
            authorize.requestMatchers("/actuator/**", "/v3/api-docs/**", "/api/v1/openapi.json", "/api/v1/openapi.json/**").denyAll();
            authorize.anyRequest().authenticated();
        });
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles"); authorities.setAuthorityPrefix("ROLE_");
        var platformAuthorities = new JwtGrantedAuthoritiesConverter();
        platformAuthorities.setAuthoritiesClaimName("platformRoles"); platformAuthorities.setAuthorityPrefix("ROLE_");
        var scopes = new JwtGrantedAuthoritiesConverter();
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp"))) {
                return java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER"));
            }
            var combined = new java.util.HashSet<org.springframework.security.core.GrantedAuthority>(authorities.convert(jwt));
            combined.addAll(platformAuthorities.convert(jwt));
            combined.addAll(scopes.convert(jwt));
            return combined;
        });
        // E5-T24: the bearer of a signed file URL is never read, so it does no harm (an expired one included).
        var bearers = new org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver();
        http.oauth2ResourceServer(resource -> resource.bearerTokenResolver(request -> com.agilityhub.core.shared.api.SignedFileRequests.matches(request) ? null : bearers.resolve(request))
                .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((request, response, exception) -> writeError(request, response, ErrorCode.UNAUTHENTICATED, errors, mapper))
                .accessDeniedHandler((request, response, exception) -> writeError(request, response, ErrorCode.FORBIDDEN, errors, mapper)));
        http.exceptionHandling(config -> config
                .authenticationEntryPoint((request, response, exception) -> writeError(request, response, ErrorCode.UNAUTHENTICATED, errors, mapper))
                .accessDeniedHandler((request, response, exception) -> writeError(request, response, ErrorCode.FORBIDDEN, errors, mapper)));
        var tenant = tenants.getIfAvailable();
        http.addFilterAfter(rateLimits.getFilter(), BearerTokenAuthenticationFilter.class);
        if (tenant != null) {
            http.addFilterAfter(tenant.getFilter(), RateLimitFilter.class);
            http.addFilterAfter(new com.agilityhub.core.identity.api.CurrentUserFilter(impersonations), TenantFilter.class);
        }
        http.addFilterAfter(new com.agilityhub.core.shared.api.AccountStateFilter(accountAccess, errors, mapper), RateLimitFilter.class);
        return http.build();
    }
    static void writeError(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                           ErrorCode code, ApiExceptionHandler errors, ObjectMapper mapper) throws java.io.IOException {
        response.setStatus(code.httpStatus()); response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), errors.body(new ApiException(code), request));
    }
}

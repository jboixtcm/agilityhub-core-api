package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.api.PlatformClubContracts;
import com.agilityhub.core.platform.application.definition.PlatformDefinitionSchema;
import com.agilityhub.core.shared.api.ApiExceptionHandler;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

@Configuration(proxyBeanMethods=false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class E10ContractConfiguration {
    /**
     * Existing deployment ingress rejects /internal/*; the Caddy ask listener is loopback-only. INC-64: Caddy's `forward_auth` to
     * `/internal/domains/app` (which SPA a club host serves) answers under the same rule.
     */
    @Bean @Order(1)
    SecurityFilterChain internalDomainSecurity(HttpSecurity http,ApiExceptionHandler errors,ObjectMapper mapper) throws Exception {
        var peers=List.of("127.0.0.0/8","::1/128","10.0.0.0/8","172.16.0.0/12","192.168.0.0/16").stream().map(IpAddressMatcher::new).toList();
        http.securityMatcher("/internal/domains/allowed","/internal/domains/app")
                .csrf(csrf->csrf.disable()).requestCache(cache->cache.disable())
                .sessionManagement(sessions->sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth.requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/domains/allowed","/internal/domains/app")
                        .access((authentication,context)->new AuthorizationDecision(peers.stream().anyMatch(peer->peer.matches(context.getRequest().getRemoteAddr()))))
                        .anyRequest().denyAll())
                .exceptionHandling(errorsConfig->errorsConfig
                        .authenticationEntryPoint((request,response,error)->SecurityConfiguration.writeError(request,response,ErrorCode.FORBIDDEN,errors,mapper))
                        .accessDeniedHandler((request,response,error)->SecurityConfiguration.writeError(request,response,ErrorCode.FORBIDDEN,errors,mapper)));
        return http.build();
    }
    @Bean OpenApiCustomizer e10Schemas(PlatformDefinitionSchema definitions) {
        return api->{
            try {
                api.getComponents().addSchemas("ClubDefinition",Json31.mapper().readValue(definitions.document().toString(),Schema.class));
            } catch(java.io.IOException invalid) { throw new IllegalStateException("Invalid ClubDefinition schema",invalid); }
            new NullableReferences(Arrays.stream(PlatformClubContracts.class.getDeclaredClasses()).map(Class::getSimpleName).toList()).customise(api);
        };
    }
}

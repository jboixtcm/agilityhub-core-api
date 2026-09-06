package com.agilityhub.core.configuration;

import com.agilityhub.core.identity.api.PasswordGrantConverter;
import com.agilityhub.core.identity.api.PasswordGrantProvider;
import com.agilityhub.core.identity.application.TokenService;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.identity.persistence.RefreshTokenRepository;
import com.agilityhub.core.shared.api.ApiExceptionHandler;
import com.agilityhub.core.shared.api.TenantFilter;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.web.NimbusJwkSetEndpointFilter;
import org.springframework.security.oauth2.server.authorization.web.OAuth2TokenEndpointFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/** E0 exposes only SAS token/JWKS filters; full OIDC endpoints and client registration are E1. */
@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {
    @Bean @Order(0) ApplicationRunner identityIndexes(AccountRepository accounts, MembershipRepository memberships, RefreshTokenRepository refresh) {
        return args -> { accounts.ensureIndexes(); memberships.ensureIndexes(); refresh.ensureIndexes(); };
    }
    @Bean RegisteredClientRepository registeredClients() {
        return new InMemoryRegisteredClientRepository(client("clubs-app"), client("clubs-admin"));
    }
    private RegisteredClient client(String id) {
        return RegisteredClient.withId(id).clientId(id).clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(new AuthorizationGrantType("password"))
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN).build();
    }
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    @Bean @Order(1)
    SecurityFilterChain oauthEndpoints(HttpSecurity http, TokenService tokens, RegisteredClientRepository clients,
            JWKSource<SecurityContext> keys, FilterRegistrationBean<TenantFilter> tenants,
            ApiExceptionHandler errors, ObjectMapper mapper) throws Exception {
        var token = new OAuth2TokenEndpointFilter(new ProviderManager(new PasswordGrantProvider(tokens, clients)));
        token.setAuthenticationConverter(new PasswordGrantConverter());
        token.setAuthenticationFailureHandler((request, response, exception) -> {
            String code = ((OAuth2AuthenticationException) exception).getError().getErrorCode();
            ErrorCode catalog;
            try { catalog = ErrorCode.valueOf(code); }
            catch (IllegalArgumentException unmapped) {
                catalog = "invalid_client".equals(code) ? ErrorCode.INVALID_CREDENTIALS : ErrorCode.VALIDATION_ERROR;
            }
            SecurityConfiguration.writeError(request, response, catalog, errors, mapper);
        });
        http.securityMatcher("/oauth2/token", "/oauth2/jwks", "/.well-known/jwks.json")
                .csrf(csrf -> csrf.disable()).sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        var oauthJwks = new NimbusJwkSetEndpointFilter(keys);
        oauthJwks.setBeanName("oauthJwks");
        var wellKnownJwks = new NimbusJwkSetEndpointFilter(keys, "/.well-known/jwks.json");
        wellKnownJwks.setBeanName("wellKnownJwks");
        http.addFilterBefore(oauthJwks, AnonymousAuthenticationFilter.class);
        http.addFilterBefore(wellKnownJwks, AnonymousAuthenticationFilter.class);
        http.addFilterBefore(tenants.getFilter(), AnonymousAuthenticationFilter.class);
        http.addFilterAfter(token, TenantFilter.class);
        return http.build();
    }
}

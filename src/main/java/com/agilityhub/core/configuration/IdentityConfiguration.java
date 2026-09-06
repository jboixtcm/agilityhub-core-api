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

/** Identity token/JWKS filters; full OIDC and persistent client registration follow in E1-T05. */
@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {
    @Bean @Order(0) ApplicationRunner identityIndexes(AccountRepository accounts, MembershipRepository memberships, RefreshTokenRepository refresh, com.agilityhub.core.identity.persistence.MagicLinkTokenRepository magic) {
        return args -> { accounts.ensureIndexes(); memberships.ensureIndexes(); refresh.ensureIndexes(); magic.ensureIndexes(); };
    }
    @Bean(name = "magicLinkExecutor", destroyMethod = "shutdown")
    java.util.concurrent.ThreadPoolExecutor magicLinkExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1000), runnable -> {
                    var thread = new Thread(runnable, "identity-magic-link"); thread.setDaemon(true); return thread;
                });
    }
    @Bean RegisteredClientRepository registeredClients() {
        return new InMemoryRegisteredClientRepository(client("clubs-app"), client("clubs-admin"), client("id-web"));
    }
    private RegisteredClient client(String id) {
        var client = RegisteredClient.withId(id).clientId(id).clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(new AuthorizationGrantType(com.agilityhub.core.identity.application.MagicLinkService.GRANT))
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        if (!id.equals("id-web")) { client.authorizationGrantType(new AuthorizationGrantType("password")); }
        return client.build();
    }
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    @Bean @Order(1)
    SecurityFilterChain oauthEndpoints(HttpSecurity http, TokenService tokens, RegisteredClientRepository clients,
            JWKSource<SecurityContext> keys, FilterRegistrationBean<TenantFilter> tenants,
            FilterRegistrationBean<com.agilityhub.core.shared.api.RateLimitFilter> rateLimits,
            org.springframework.core.env.Environment environment, org.springframework.web.cors.CorsConfigurationSource clubCors,
            ApiExceptionHandler errors, ObjectMapper mapper) throws Exception {
        SecurityBaselineConfiguration.headersAndCors(http, environment, clubCors);
        var token = new OAuth2TokenEndpointFilter(new ProviderManager(new PasswordGrantProvider(tokens, clients)));
        token.setAuthenticationConverter(new PasswordGrantConverter());
        token.setAuthenticationFailureHandler((request, response, exception) -> {
            String code = ((OAuth2AuthenticationException) exception).getError().getErrorCode();
            ErrorCode catalog;
            try { catalog = ErrorCode.valueOf(code); }
            catch (IllegalArgumentException unmapped) {
                catalog = "invalid_client".equals(code) ? ErrorCode.INVALID_CREDENTIALS : ErrorCode.VALIDATION_ERROR;
            }
            var failure = exception.getCause() instanceof com.agilityhub.core.shared.domain.ApiException api
                    ? api : new com.agilityhub.core.shared.domain.ApiException(catalog);
            if (failure.details().get("retryAfter") instanceof Number retry) { response.setHeader("Retry-After", retry.toString()); }
            response.setStatus(catalog.httpStatus()); response.setContentType("application/json"); response.setHeader("Cache-Control", "no-store");
            mapper.writeValue(response.getOutputStream(), errors.body(failure, request));
        });
        // Pending E1 grants reach the typed MVC contract and its standard 501 response.
        http.securityMatcher(request -> {
            String path = request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath();
            String grant = request.getParameter("grant_type");
            return path.equals("/oauth2/jwks") || path.equals("/.well-known/jwks.json")
                    || (path.equals("/oauth2/token") && !"urn:agilityhub:grant:handoff".equals(grant) && !"authorization_code".equals(grant));
        })
                .csrf(csrf -> csrf.disable()).sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        var oauthJwks = new NimbusJwkSetEndpointFilter(keys);
        oauthJwks.setBeanName("oauthJwks");
        var wellKnownJwks = new NimbusJwkSetEndpointFilter(keys, "/.well-known/jwks.json");
        wellKnownJwks.setBeanName("wellKnownJwks");
        http.addFilterBefore(oauthJwks, AnonymousAuthenticationFilter.class);
        http.addFilterBefore(wellKnownJwks, AnonymousAuthenticationFilter.class);
        http.addFilterBefore(rateLimits.getFilter(), AnonymousAuthenticationFilter.class);
        http.addFilterAfter(tenants.getFilter(), com.agilityhub.core.shared.api.RateLimitFilter.class);
        http.addFilterAfter(token, TenantFilter.class);
        return http.build();
    }
}

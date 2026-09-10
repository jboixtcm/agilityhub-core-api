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

/** Identity grants and public verification keys. */
@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {
    @Bean @Order(0) ApplicationRunner identityIndexes(AccountRepository accounts, MembershipRepository memberships, RefreshTokenRepository refresh, com.agilityhub.core.identity.persistence.MagicLinkTokenRepository magic,
            com.agilityhub.core.identity.persistence.ImpersonationGrantRepository impersonations, com.agilityhub.core.identity.persistence.HandoffCodeRepository handoffs, com.agilityhub.core.identity.application.OidcService oidc) {
        return args -> { accounts.ensureIndexes(); memberships.ensureIndexes(); refresh.ensureIndexes(); magic.ensureIndexes(); impersonations.ensureIndexes(); handoffs.ensureIndexes(); oidc.initialize(); };
    }
    @Bean(name = "magicLinkExecutor", destroyMethod = "shutdown")
    java.util.concurrent.ThreadPoolExecutor magicLinkExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1000), runnable -> {
                    var thread = new Thread(runnable, "identity-magic-link"); thread.setDaemon(true); return thread;
                });
    }
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    @Bean @Order(1)
    SecurityFilterChain oauthEndpoints(HttpSecurity http, TokenService tokens, RegisteredClientRepository clients,
            JWKSource<SecurityContext> keys, com.agilityhub.core.identity.application.OidcService oidc, FilterRegistrationBean<TenantFilter> tenants,
            FilterRegistrationBean<com.agilityhub.core.shared.api.RateLimitFilter> rateLimits,
            org.springframework.core.env.Environment environment, org.springframework.web.cors.CorsConfigurationSource clubCors,
            ApiExceptionHandler errors, ObjectMapper mapper, com.agilityhub.core.identity.application.HandoffService handoffs,
            com.agilityhub.core.identity.api.RefreshCookies cookies) throws Exception {
        SecurityBaselineConfiguration.headersAndCors(http, environment, clubCors);
        var token = new OAuth2TokenEndpointFilter(new ProviderManager(new PasswordGrantProvider(tokens, clients, handoffs, oidc)));
        token.setAuthenticationConverter(new PasswordGrantConverter(cookies));
        var standardResponse = new org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AccessTokenResponseAuthenticationSuccessHandler();
        standardResponse.setAccessTokenResponseCustomizer(context -> {
            var builder = context.getAccessTokenResponse();
            var issued = builder.build();
            if (issued.getAccessToken().getScopes().isEmpty()) {
                // The standard converter omits empty scopes; our published contract requires the field.
                var additional = new java.util.LinkedHashMap<>(issued.getAdditionalParameters());
                additional.put("scope", "");
                builder.additionalParameters(additional);
            }
        });
        token.setAuthenticationSuccessHandler((request, response, authentication) -> {
            var issued = (org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken) authentication;
            if (com.agilityhub.core.identity.application.TokenDelivery.cookie(issued.getRegisteredClient()) && issued.getRefreshToken() != null) {
                cookies.issue(request, response, issued.getRefreshToken());
                authentication = new org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken(
                        issued.getRegisteredClient(), (org.springframework.security.core.Authentication) issued.getPrincipal(),
                        issued.getAccessToken(), null, issued.getAdditionalParameters());
            }
            standardResponse.onAuthenticationSuccess(request, response, authentication);
        });
        token.setAuthenticationFailureHandler((request, response, exception) -> {
            String code = ((OAuth2AuthenticationException) exception).getError().getErrorCode();
            ErrorCode catalog;
            try { catalog = ErrorCode.valueOf(code); }
            catch (IllegalArgumentException unmapped) {
                catalog = "invalid_client".equals(code) ? ErrorCode.INVALID_CREDENTIALS : ErrorCode.VALIDATION_ERROR;
            }
            if ((catalog == ErrorCode.REFRESH_EXPIRED || catalog == ErrorCode.REFRESH_REUSED)
                    && cookies.cookieClient(request.getParameter("client_id"))) { cookies.clear(request, response); }
            var failure = exception.getCause() instanceof com.agilityhub.core.shared.domain.ApiException api
                    ? api : new com.agilityhub.core.shared.domain.ApiException(catalog);
            if (failure.details().get("retryAfter") instanceof Number retry) { response.setHeader("Retry-After", retry.toString()); }
            response.setStatus(catalog.httpStatus()); response.setContentType("application/json"); response.setHeader("Cache-Control", "no-store");
            mapper.writeValue(response.getOutputStream(), errors.body(failure, request));
        });
        // All token grants use the same Spring Authorization Server response and error filters.
        http.securityMatcher(request -> {
            String path = request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath();
            String grant = request.getParameter("grant_type");
            return path.equals("/oauth2/jwks") || path.equals("/.well-known/jwks.json")
                    || (path.equals("/oauth2/token"));
        })
                .csrf(csrf -> csrf.disable()).sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        // Validate ambient credentials before CORS so every invalid cookie grant uses the catalog envelope.
        http.addFilterBefore(new org.springframework.web.filter.OncePerRequestFilter() {
            @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.FilterChain chain)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                if (cookies.cookieRefresh(request)) {
                    try { cookies.read(request); }
                    catch (com.agilityhub.core.shared.domain.ApiException failure) {
                        if (cookies.cookieClient(request.getParameter("client_id"))) { cookies.clear(request, response); }
                        SecurityConfiguration.writeError(request, response, failure.code(), errors, mapper);
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        }, org.springframework.web.filter.CorsFilter.class);
        http.addFilterBefore(rateLimits.getFilter(), AnonymousAuthenticationFilter.class);
        http.addFilterAfter(tenants.getFilter(), com.agilityhub.core.shared.api.RateLimitFilter.class);
        http.addFilterAfter(token, TenantFilter.class);
        return http.build();
    }
}

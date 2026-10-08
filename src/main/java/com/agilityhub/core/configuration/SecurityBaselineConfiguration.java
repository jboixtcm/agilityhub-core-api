package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.api.ClubCorsConfigurationSource;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.platform.persistence.SecurityEventRepository;
import com.agilityhub.core.shared.api.ApiExceptionHandler;
import com.agilityhub.core.shared.api.RateLimitFilter;
import com.agilityhub.core.shared.application.RateLimits;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.SecurityRequestProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityBaselineConfiguration.RateLimitConfiguration.class)
public class SecurityBaselineConfiguration {
    @ConfigurationProperties("core.security.rate-limits")
    public record RateLimitConfiguration(boolean enabled, RateLimits.Limit token, RateLimits.Limit branding,
                                         RateLimits.Limit publicRoutes, RateLimits.Limit me,
                                         RateLimits.Limit anonymous, RateLimits.Limit webhook, RateLimits.Limit signedFile,
                                         RateLimits.Limit handoff, RateLimits.Limit tokenAccount,
                                         RateLimits.Limit checkoutStatus, RateLimits.Limit stripeWebhook,
                                         RateLimits.Limit magicLinkEmail, RateLimits.Limit magicLinkIp) { }

    @Bean RateLimits rateLimits(RateLimitConfiguration settings, Clock clock) {
        var limits = new java.util.EnumMap<RateLimits.Route, RateLimits.Limit>(RateLimits.Route.class);
        limits.put(RateLimits.Route.TOKEN, settings.token());
        limits.put(RateLimits.Route.BRANDING, settings.branding());
        limits.put(RateLimits.Route.PUBLIC, settings.publicRoutes());
        limits.put(RateLimits.Route.ME, settings.me());
        limits.put(RateLimits.Route.ANONYMOUS, settings.anonymous());
        limits.put(RateLimits.Route.WEBHOOK, settings.webhook());
        limits.put(RateLimits.Route.SIGNED_FILE, settings.signedFile());
        limits.put(RateLimits.Route.HANDOFF, settings.handoff());
        limits.put(RateLimits.Route.TOKEN_ACCOUNT, settings.tokenAccount());
        limits.put(RateLimits.Route.CHECKOUT_STATUS, settings.checkoutStatus());
        limits.put(RateLimits.Route.STRIPE_WEBHOOK, settings.stripeWebhook());
        limits.put(RateLimits.Route.MAGIC_LINK_EMAIL, settings.magicLinkEmail());
        limits.put(RateLimits.Route.MAGIC_LINK_IP, settings.magicLinkIp());
        return new RateLimits(settings.enabled(), limits, clock);
    }

    @Bean FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimits limits, SecurityEvents events,
                                                                 ApiExceptionHandler errors, ObjectMapper mapper, HostTenantResolver hosts, Environment env,
                                                                 com.agilityhub.core.platform.application.ClubConfigService configs) {
        var filter=new RateLimitFilter(limits,events,errors,mapper, com.agilityhub.core.identity.domain.Email::normalize);
        filter.signupHosts(hosts,env.acceptsProfiles(Profiles.of("local")));
        // R-04-20 (E3-T09): the per-club `signup.rateLimit` (cached club configuration, evicted after each parameter write).
        // A club that cannot be loaded keeps the defaults: the tenant filter that follows answers for it.
        filter.signupParameter(clubId -> {
            try { return configs.get(clubId).get("signup.rateLimit", Map.class); } catch (RuntimeException unknown) { return null; }
        });
        var registration = new FilterRegistrationBean<>(filter);
        // Install only in the security chains, after bearer authentication and before tenant lookup.
        registration.setEnabled(false);
        return registration;
    }

    @Bean CorsConfigurationSource clubCors(HostTenantResolver hosts, Environment environment,
            @Value("${core.security.cors.platform-hosts}") List<String> platformHosts) {
        return new ClubCorsConfigurationSource(hosts, platformHosts, environment.acceptsProfiles(Profiles.of("local")));
    }

    @Bean ApplicationRunner securityEventIndexes(SecurityEventRepository events, ParameterCatalog catalog,
            Environment environment) {
        int retentionDays = environment.getProperty("security.eventRetentionDays", Integer.class,
                ((Number) catalog.get("security.eventRetentionDays").defaultValue()).intValue());
        return args -> events.ensureIndexes(retentionDays);
    }

    @Bean SecurityRequestProvider securityRequestProvider() {
        return () -> {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servlet)) {
                return new SecurityRequestProvider.Request(null, "background");
            }
            var request = servlet.getRequest();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            String route = switch (path) {
                case "/oauth2/token", "/api/v1/branding" -> path;
                default -> path.equals("/api/v1/me") || path.startsWith("/api/v1/me/") ? "/api/v1/me/**"
                        : path.equals("/api/v1/public") || path.startsWith("/api/v1/public/") ? "/api/v1/public/**" : "other";
            };
            // Tomcat processes forwarding only from explicitly configured trusted proxies.
            return new SecurityRequestProvider.Request(request.getRemoteAddr(), route);
        };
    }

    static void headersAndCors(HttpSecurity http, Environment environment, CorsConfigurationSource cors) throws Exception {
        http.cors(config -> config.configurationSource(cors));
        http.headers(headers -> {
            headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
            // A download sets its own stricter policy (ContentSecurityPolicies.DOWNLOAD); the writer leaves an existing one.
            headers.contentSecurityPolicy(csp -> csp.policyDirectives(com.agilityhub.core.shared.application.ContentSecurityPolicies.API));
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                headers.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000));
            } else { headers.httpStrictTransportSecurity(hsts -> hsts.disable()); }
        });
    }
}

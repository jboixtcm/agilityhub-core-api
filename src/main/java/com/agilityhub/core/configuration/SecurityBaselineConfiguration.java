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
                                         RateLimits.Limit publicRoutes, RateLimits.Limit me) { }

    @Bean RateLimits rateLimits(RateLimitConfiguration settings, Clock clock) {
        return new RateLimits(settings.enabled(), Map.of(RateLimits.Route.TOKEN, settings.token(),
                RateLimits.Route.BRANDING, settings.branding(), RateLimits.Route.PUBLIC, settings.publicRoutes(),
                RateLimits.Route.ME, settings.me(),
                RateLimits.Route.MAGIC_LINK_EMAIL, new RateLimits.Limit(10, java.time.Duration.ofHours(1)),
                RateLimits.Route.MAGIC_LINK_IP, new RateLimits.Limit(60, java.time.Duration.ofHours(1))), clock);
    }

    @Bean FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimits limits, SecurityEvents events,
                                                                 ApiExceptionHandler errors, ObjectMapper mapper, HostTenantResolver hosts, Environment env) {
        var filter=new RateLimitFilter(limits,events,errors,mapper);
        filter.signupHosts(hosts,env.acceptsProfiles(Profiles.of("local")));
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
            headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"));
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                headers.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000));
            } else { headers.httpStrictTransportSecurity(hsts -> hsts.disable()); }
        });
    }
}

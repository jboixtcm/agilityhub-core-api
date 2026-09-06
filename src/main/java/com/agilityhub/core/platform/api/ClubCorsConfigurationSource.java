package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.HostTenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

public final class ClubCorsConfigurationSource implements CorsConfigurationSource {
    private final HostTenantResolver hosts;
    private final List<String> platformHosts;
    private final boolean local;

    public ClubCorsConfigurationSource(HostTenantResolver hosts, List<String> platformHosts, boolean local) {
        this.hosts = hosts; this.platformHosts = List.copyOf(platformHosts); this.local = local;
    }

    @Override public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        var configuration = new CorsConfiguration();
        String origin = request.getHeader("Origin");
        if (allowed(origin)) { configuration.setAllowedOrigins(List.of(origin)); }
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Accept-Language",
                "Idempotency-Key", "If-Match", "If-None-Match"));
        configuration.setExposedHeaders(List.of("Retry-After", "ETag", "Content-Language"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(300L);
        return configuration;
    }

    private boolean allowed(String origin) {
        if (origin == null) { return false; }
        try {
            URI uri = URI.create(origin);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) { return false; }
            if (!("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))) { return false; }
            if (!local && uri.getPort() != -1 && uri.getPort() != 443) { return false; }
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            return platformHosts.contains(host) || hosts.isCorsHost(host, local);
        } catch (IllegalArgumentException invalid) { return false; }
    }
}

package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.HostTenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

public final class ClubCorsConfigurationSource implements CorsConfigurationSource {
    private static final org.springframework.web.util.UrlPathHelper PATHS = new org.springframework.web.util.UrlPathHelper();
    private final HostTenantResolver hosts;
    private final List<String> platformHosts;
    private final boolean local;

    public ClubCorsConfigurationSource(HostTenantResolver hosts, List<String> platformHosts, boolean local) {
        this.hosts = hosts; this.platformHosts = List.copyOf(platformHosts); this.local = local;
    }

    @Override public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        // E5-T27 round 2 (review #2, ruling E71): the health never reaches the database, not even to resolve CORS. Only the configured
        // platform hosts are allowed there; any other origin gets no CORS processing, so the probe still answers its UP/DOWN envelope.
        if (health(request)) { return platform(origin) ? configuration(origin) : null; }
        return configuration(allowed(origin, request) ? origin : null);
    }

    /** E5-T27 round 3: the path Spring routes (decoded, without `;` parameters), so `/api/v1/%68ealth` is the health too. */
    private static boolean health(HttpServletRequest request) {
        return PATHS.getPathWithinApplication(request).equals("/api/v1/health");
    }

    private CorsConfiguration configuration(String allowedOrigin) {
        var configuration = new CorsConfiguration();
        if (allowedOrigin != null) { configuration.setAllowedOrigins(List.of(allowedOrigin)); }
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Accept-Language",
                "Idempotency-Key", "If-Match", "If-None-Match"));
        // E5-T27 step 8 (ruling E61, CONVENCIONS_API §5): the web reads a download's stored name when it fetches the file.
        configuration.setExposedHeaders(List.of("Retry-After", "ETag", "Content-Language", "Content-Disposition"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(300L);
        return configuration;
    }

    private boolean allowed(String origin, HttpServletRequest request) {
        String host = host(origin);
        if (host == null) { return false; }
        if (platformHosts.contains(host)) { return true; }
        var originClub = hosts.corsClub(host, local);
        if (originClub.isEmpty()) { return false; }
        // Global identity/core endpoints accept registered club origins. A host-selected club accepts only its own.
        String targetHost = local && request.getHeader("X-Club-Host") != null
                ? request.getHeader("X-Club-Host") : request.getHeader("Host");
        var targetClub = hosts.resolve(targetHost);
        return targetClub.isEmpty() || targetClub.equals(originClub);
    }

    private boolean platform(String origin) {
        String host = host(origin);
        return host != null && platformHosts.contains(host);
    }

    /** The origin's host when the origin is well formed (scheme, port, no path/query/fragment/user info); otherwise null. */
    private String host(String origin) {
        if (origin == null) { return null; }
        try {
            URI uri = URI.create(origin);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) { return null; }
            if (!("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))) { return null; }
            if (!local && uri.getPort() != -1 && uri.getPort() != 443) { return null; }
            return uri.getHost().toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException invalid) { return null; }
    }
}

package com.agilityhub.core.shared.api;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Liveness routes must not load account or club data, even with ambient credentials. E5-T27 round 3: the bearer resolver
 * never reads their `Authorization` header either. The route is matched on the path Spring routes (decoded, without `;`
 * parameters), so that `/api/v1/%68ealth`, which MVC also sends to the health, is the same route here.
 */
public final class HealthRequests {
    private static final org.springframework.web.util.UrlPathHelper PATHS = new org.springframework.web.util.UrlPathHelper();
    private HealthRequests() { }

    public static boolean matches(HttpServletRequest request) {
        return PATHS.getPathWithinApplication(request).equals("/api/v1/health");
    }
}

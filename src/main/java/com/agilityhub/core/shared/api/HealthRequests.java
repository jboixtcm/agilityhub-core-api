package com.agilityhub.core.shared.api;

import jakarta.servlet.http.HttpServletRequest;

/** Liveness routes must not load account or club data, even with ambient credentials. */
final class HealthRequests {
    private HealthRequests() { }

    static boolean matches(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/api/v1/health");
    }
}

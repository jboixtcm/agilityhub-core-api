package com.agilityhub.core.shared.application;

@FunctionalInterface
public interface SecurityRequestProvider {
    Request current();
    /**
     * Route is a fixed route family, never a query string or user-supplied path segment. `userAgent` is the client's header, cut
     * at {@link #USER_AGENT_LENGTH} characters (S14 §3 `SecurityEvent.userAgent`).
     */
    record Request(String ip, String route, String userAgent) {
        public Request(String ip, String route) { this(ip, route, null); }
    }
    int USER_AGENT_LENGTH = 256;
}

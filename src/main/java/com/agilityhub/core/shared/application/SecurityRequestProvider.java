package com.agilityhub.core.shared.application;

@FunctionalInterface
public interface SecurityRequestProvider {
    Request current();
    /** Route is a fixed route family, never a query string or user-supplied path segment. */
    record Request(String ip, String route) { }
}

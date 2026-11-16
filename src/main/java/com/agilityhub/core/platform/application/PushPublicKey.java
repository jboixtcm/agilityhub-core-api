package com.agilityhub.core.platform.application;

/** Public branding port: the active transport's key for the current club, or null when unavailable. */
@FunctionalInterface
public interface PushPublicKey {
    String current();
}

package com.agilityhub.core.shared.application;

import java.time.Instant;

/** Identity-owned status check used by the resource-server boundary. */
@FunctionalInterface
public interface AccountAccess {
    boolean blocked(String accountId, Instant issuedAt);
}

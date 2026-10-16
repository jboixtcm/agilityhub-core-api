package com.agilityhub.core.platform.application;

import java.time.Instant;

/**
 * S17 R-17-10 «Entra com a administrador del club» (E10-T04). Identity owns the grant and its token; the console only asks
 * for one, in the target club's scope, for the calling platform administrator.
 */
public interface ConsoleSupportAccess {
    /** The token is answered once to its caller; it is never audited nor logged. */
    record Issued(String grantId, String token, Instant expiresAt) {
        @Override public String toString() { return "Issued[redacted]"; }
    }
    Issued start(String reason);
}

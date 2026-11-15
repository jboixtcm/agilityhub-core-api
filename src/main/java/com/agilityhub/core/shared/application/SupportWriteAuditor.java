package com.agilityhub.core.shared.application;

import java.time.Instant;

/**
 * S17 R-17-10 (AUTHZ-05, owner decision 13-11-2026): every write made with a support access leaves an entry with `origin =
 * PLATFORM_SUPPORT` in the club's audit, also one whose service audits nothing. The web layer calls this port around each support
 * write; the audit context implements it.
 */
public interface SupportWriteAuditor {
    /** The moment a support write starts: the audit entries its request writes from then on are its own. */
    Instant started();

    /**
     * After a support write answered 2xx: unless the request left an audit entry of its own since {@code since}, records that
     * the write happened (`method`, the matched `route`, the `path` and the `status`) on the support access {@code grantId}.
     * Returns whether it wrote an entry.
     */
    boolean record(String grantId, Instant since, String method, String route, String path, int status);
}

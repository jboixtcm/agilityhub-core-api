package com.agilityhub.core.platform.application;

import java.util.List;

/**
 * S17 R-17-03's DNS lookups, behind a port (E10-T04): production asks the resolver of the host (`JndiDomainDns`); the local
 * and test profiles answer from {@link FakeDomainDns}, so no test makes a real DNS call.
 * <p>
 * E10-T03 round 2 (review #1): an answer without a matching record (NXDOMAIN, or no record of that type) is an empty list;
 * a lookup that got no answer (a timeout, SERVFAIL, no network) throws {@link LookupFailed}, because nothing is known about
 * the records then. The console's verification treats both as «not verified» (`422 HOST_NOT_VERIFIED`); S15 P9's re-check
 * counts only the first as a failure and skips the domain on the second.
 */
public interface DomainDns {
    /** The CNAME targets of `host`. */
    List<String> cnames(String host);
    /** The TXT strings of `name`. */
    List<String> txt(String name);
    /**
     * The IPv4 and IPv6 addresses `host` resolves to, CNAMEs followed (E10-T03): S15 P9's re-check of a verified apex domain,
     * which has no CNAME and whose TXT challenge was removed when it was verified.
     */
    List<String> addresses(String host);

    /** The resolver gave no answer for `type`: the records are unknown, not missing. */
    final class LookupFailed extends RuntimeException {
        private final String type;
        public LookupFailed(String type, Throwable cause) { super("DNS lookup failed: type=" + type, cause); this.type = type; }
        public String type() { return type; }
    }
}

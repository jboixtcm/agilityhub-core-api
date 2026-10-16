package com.agilityhub.core.platform.application;

import java.util.List;

/**
 * S17 R-17-03's DNS lookups, behind a port (E10-T04): production asks the resolver of the host (`JndiDomainDns`); the local
 * and test profiles answer from {@link FakeDomainDns}, so no test makes a real DNS call. A failed or empty lookup is an empty
 * list, never an error: the verification then answers `422 HOST_NOT_VERIFIED`.
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
}

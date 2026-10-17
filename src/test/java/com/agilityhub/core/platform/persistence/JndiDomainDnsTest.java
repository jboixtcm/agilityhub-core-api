package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.platform.domain.DomainChallenge;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * S17 R-17-03 (E10-T04 round 2, review #7): the JDK's DNS provider answers a TXT record split into several character strings
 * as those strings separated by a space, each one quoted only when it holds a space, a quote or a backslash, or is empty. The
 * record's value is the strings joined without anything in between (as RFC 7208 §3.3 joins them).
 */
class JndiDomainDnsTest {
    @Test void T_17_03_R_17_03_aTxtRecordSplitIntoSeveralStringsIsJoinedWithoutSpaces() {
        assertThat(JndiDomainDns.joined("agh-verify-0123456789 abcdef")).isEqualTo("agh-verify-0123456789abcdef");
        assertThat(JndiDomainDns.joined("\"agh-verify-0123456789\" \"abcdef\"")).isEqualTo("agh-verify-0123456789abcdef");
        assertThat(JndiDomainDns.joined("agh-verify-0123456789abcdef")).isEqualTo("agh-verify-0123456789abcdef");
        assertThat(JndiDomainDns.joined("\"v=spf1 include:example.test\" -all")).isEqualTo("v=spf1 include:example.test-all");
        assertThat(JndiDomainDns.joined("\"say \\\"hi\\\"\" \"\\\\\"")).isEqualTo("say \"hi\"\\");
        assertThat(JndiDomainDns.joined("\"\"")).isEmpty();
        assertThat(DomainChallenge.txtMatches(List.of(JndiDomainDns.joined("agh-verify-0123456789 abcdef")), "0123456789abcdef")).isTrue();
        assertThat(DomainChallenge.txtMatches(List.of(JndiDomainDns.joined("agh-verify-0123456789 abcdef")), "0123456789")).isFalse();
    }
}

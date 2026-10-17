package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.platform.domain.DomainChallenge;
import com.agilityhub.core.platform.application.DomainDns;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import javax.naming.CommunicationException;
import javax.naming.NameNotFoundException;
import javax.naming.ServiceUnavailableException;
import javax.naming.directory.BasicAttribute;
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

    static final JndiDomainDns.Addresses NO_ADDRESS = host -> { throw new UnknownHostException(host); };

    /** E10-T03 round 2 (review #1): an answer without the record is an empty list; a lookup without an answer throws. */
    @Test void T_17_03_R_17_03_anAnswerWithoutTheRecordIsEmptyAndALookupWithoutAnAnswerFails() {
        var answered = new JndiDomainDns((name, type) -> new BasicAttribute(type, "clubs.agilitydoghub.com."), NO_ADDRESS);
        assertThat(answered.cnames("app.example.test")).containsExactly("clubs.agilitydoghub.com.");
        // No record of the type: the provider answers without the attribute.
        assertThat(new JndiDomainDns((name, type) -> null, NO_ADDRESS).cnames("app.example.test")).isEmpty();
        // NXDOMAIN.
        var nxdomain = new JndiDomainDns((name, type) -> { throw new NameNotFoundException(name); }, NO_ADDRESS);
        assertThat(nxdomain.cnames("app.example.test")).isEmpty();
        assertThat(nxdomain.txt("_agilityhub-verify.app.example.test")).isEmpty();
        // A timeout (CommunicationException) and SERVFAIL (ServiceUnavailableException): nothing is known.
        var timeout = new JndiDomainDns((name, type) -> { throw new CommunicationException("timeout"); }, NO_ADDRESS);
        assertThatThrownBy(() -> timeout.cnames("app.example.test")).isInstanceOf(DomainDns.LookupFailed.class)
                .satisfies(failure -> assertThat(((DomainDns.LookupFailed) failure).type()).isEqualTo("CNAME"));
        var servfail = new JndiDomainDns((name, type) -> { throw new ServiceUnavailableException("SERVFAIL"); }, NO_ADDRESS);
        assertThatThrownBy(() -> servfail.txt("_agilityhub-verify.app.example.test")).isInstanceOf(DomainDns.LookupFailed.class);
    }

    /** The JVM resolver cannot tell NXDOMAIN from a failure, so the adapter asks JNDI once more when it finds nothing. */
    @Test void T_17_03_R_17_03_anAddressLookupTellsNoAddressFromNoAnswer() throws Exception {
        InetAddress[] two = {InetAddress.getByAddress("club.example.test", new byte[] {(byte) 203, 0, 113, 10}),
                InetAddress.getByAddress("club.example.test", new byte[] {(byte) 203, 0, 113, 10})};
        assertThat(new JndiDomainDns((name, type) -> { throw new AssertionError("not asked"); }, host -> two).addresses("club.example.test"))
                .containsExactly("203.0.113.10");
        assertThat(new JndiDomainDns((name, type) -> { throw new NameNotFoundException(name); }, NO_ADDRESS).addresses("club.example.test")).isEmpty();
        // An answer without addresses (a CNAME to a name that does not exist): no address either.
        assertThat(new JndiDomainDns((name, type) -> null, NO_ADDRESS).addresses("club.example.test")).isEmpty();
        var timeout = new JndiDomainDns((name, type) -> { throw new CommunicationException("timeout"); }, NO_ADDRESS);
        assertThatThrownBy(() -> timeout.addresses("club.example.test")).isInstanceOf(DomainDns.LookupFailed.class);
        // INC-66 (E11-T08): the JVM resolver found nothing but JNDI answers the name's own A records: those are its addresses,
        // never «no address» (a re-check would count our resolver's failure as a lost record).
        var answered = new JndiDomainDns((name, type) -> {
            var records = new BasicAttribute(type); records.add("203.0.113.10"); records.add("203.0.113.11"); records.add("203.0.113.10"); return records;
        }, NO_ADDRESS);
        assertThat(answered.addresses("club.example.test")).containsExactly("203.0.113.10", "203.0.113.11");
    }
}

package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.platform.application.DomainDns;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * S17 R-17-03 in staging and production: the JDK's DNS provider, with the resolvers of the host, a 2-second first timeout and
 * one retry. No record of the type, or NXDOMAIN (`NameNotFoundException`), answers an empty list; any other failure (a
 * timeout, SERVFAIL, a refused query, no network) throws {@link DomainDns.LookupFailed} (E10-T03 round 2).
 */
@Component @Profile("!((local | test) & !staging & !prod)")
public class JndiDomainDns implements DomainDns {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JndiDomainDns.class);
    /** The records of one type of a name, as the JDK's provider answers them (null: the name has none of that type). */
    interface Records { Attribute lookup(String name, String type) throws NamingException; }
    /** The JVM resolver. */
    interface Addresses { InetAddress[] resolve(String host) throws UnknownHostException; }
    private final Records records; private final Addresses resolver;

    public JndiDomainDns() { this(JndiDomainDns::query, InetAddress::getAllByName); }
    JndiDomainDns(Records records, Addresses resolver) { this.records = records; this.resolver = resolver; }

    @Override public List<String> cnames(String host) { return lookup(host, "CNAME"); }
    @Override public List<String> txt(String name) { return lookup(name, "TXT").stream().map(JndiDomainDns::joined).toList(); }
    /**
     * E10-T04 round 2: the JDK's provider answers a TXT record split into several character strings as those strings separated
     * by a space, quoting (and backslash-escaping inside) only a string that holds a space, a quote or a backslash, or is empty.
     * The record's value is its strings joined with nothing in between (RFC 7208 §3.3).
     */
    static String joined(String record) {
        var value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);
            if (quoted && c == '\\' && i + 1 < record.length()) { value.append(record.charAt(++i)); }
            else if (c == '"') { quoted = !quoted; }
            else if (quoted || c != ' ') { value.append(c); }
        }
        return value.toString();
    }
    /**
     * E10-T03: the addresses through the JVM's resolver, which follows CNAMEs (a JNDI `A` query answers only the queried name's
     * own records). The JVM's `UnknownHostException` does not tell NXDOMAIN from a failed lookup (round 2), so the name is then
     * asked once more through JNDI: an answer, or NXDOMAIN, is «no address»; no answer is {@link DomainDns.LookupFailed}.
     */
    @Override public List<String> addresses(String host) {
        try {
            return java.util.Arrays.stream(resolver.resolve(host)).map(InetAddress::getHostAddress).distinct().toList();
        } catch (UnknownHostException | SecurityException missing) {
            lookup(host, "A");
            LOG.info("Domain DNS lookup found nothing: type=ADDRESS reason={}", missing.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<String> lookup(String name, String type) {
        var values = new ArrayList<String>();
        try {
            Attribute attribute = records.lookup(name, type);
            if (attribute != null) { for (int i = 0; i < attribute.size(); i++) { values.add(String.valueOf(attribute.get(i))); } }
        } catch (NameNotFoundException missing) {
            LOG.info("Domain DNS lookup found nothing: type={} reason=NXDOMAIN", type);
        } catch (NamingException failed) {
            LOG.warn("Domain DNS lookup failed: type={} reason={}", type, failed.getClass().getSimpleName());
            throw new DomainDns.LookupFailed(type, failed);
        }
        return values;
    }
    private static Attribute query(String name, String type) throws NamingException {
        var environment = new Hashtable<String, String>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put("com.sun.jndi.dns.timeout.initial", "2000");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        var context = new InitialDirContext(environment);
        try { return context.getAttributes(name, new String[] {type}).get(type); }
        finally { context.close(); }
    }
}

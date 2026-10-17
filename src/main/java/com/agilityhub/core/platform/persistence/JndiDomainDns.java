package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.platform.application.DomainDns;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * S17 R-17-03 in staging and production: the JDK's DNS provider, with the resolvers of the host, a 2-second first timeout and
 * one retry. A lookup that fails (no record, NXDOMAIN, timeout) answers an empty list; it never throws.
 */
@Component @Profile("!((local | test) & !staging & !prod)")
public class JndiDomainDns implements DomainDns {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JndiDomainDns.class);
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
     * own records). A failed lookup is an empty list, as the others.
     */
    @Override public List<String> addresses(String host) {
        try {
            return java.util.Arrays.stream(java.net.InetAddress.getAllByName(host)).map(java.net.InetAddress::getHostAddress).distinct().toList();
        } catch (java.net.UnknownHostException | SecurityException missing) {
            LOG.info("Domain DNS lookup found nothing: type=ADDRESS reason={}", missing.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<String> lookup(String name, String type) {
        var environment = new Hashtable<String, String>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put("com.sun.jndi.dns.timeout.initial", "2000");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        var values = new ArrayList<String>();
        try {
            var context = new InitialDirContext(environment);
            try {
                Attribute attribute = context.getAttributes(name, new String[] {type}).get(type);
                if (attribute != null) { for (int i = 0; i < attribute.size(); i++) { values.add(String.valueOf(attribute.get(i))); } }
            } finally { context.close(); }
        } catch (NamingException missing) {
            LOG.info("Domain DNS lookup found nothing: type={} reason={}", type, missing.getClass().getSimpleName());
        }
        return values;
    }
}

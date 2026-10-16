package com.agilityhub.core.platform.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The local and test {@link DomainDns}: records a test publishes, nothing else (E10-T04: no real DNS from the tests).
 * <p>
 * E10-T03: a name whose addresses were never published resolves to `core.platform.fake-dns.default-addresses` (empty in
 * tests; `127.0.0.1` in the `local` profile, where every host points at the machine, so S15 P9's re-check keeps the seeded
 * clubs' trusted hosts verified on a long-lived stack). {@link #publish} replaces a record set; an empty set is an explicit
 * «no record», which also overrides the default addresses (a lost record). `POST /api/v1/test/dns` drives it on a local stack.
 */
@Component @Profile("(local | test) & !staging & !prod")
public class FakeDomainDns implements DomainDns {
    public enum Type { CNAME, TXT, A }
    private final Map<String, List<String>> cnames = new ConcurrentHashMap<>();
    private final Map<String, List<String>> txt = new ConcurrentHashMap<>();
    private final Map<String, List<String>> addresses = new ConcurrentHashMap<>();
    private final List<String> lookups = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<String> defaultAddresses;

    public FakeDomainDns(@Value("${core.platform.fake-dns.default-addresses:}") List<String> defaultAddresses) {
        this.defaultAddresses = defaultAddresses.stream().filter(value -> !value.isBlank()).toList();
    }

    public void cname(String host, String target) { cnames.computeIfAbsent(key(host), ignored -> new ArrayList<>()).add(target); }
    public void txt(String name, String value) { txt.computeIfAbsent(key(name), ignored -> new ArrayList<>()).add(value); }
    public void address(String host, String value) { addresses.computeIfAbsent(key(host), ignored -> new ArrayList<>()).add(value); }
    /** Replaces the `type` records of `name` with `values` (none = an explicit «no record»). */
    public void publish(Type type, String name, List<String> values) { records(type).put(key(name), new ArrayList<>(values)); }
    public void clear() { cnames.clear(); txt.clear(); addresses.clear(); lookups.clear(); }
    /** `CNAME host` / `TXT name` / `A host`, in the order they were asked. */
    public List<String> lookups() { return List.copyOf(lookups); }

    @Override public List<String> cnames(String host) { lookups.add("CNAME " + key(host)); return List.copyOf(cnames.getOrDefault(key(host), List.of())); }
    @Override public List<String> txt(String name) { lookups.add("TXT " + key(name)); return List.copyOf(txt.getOrDefault(key(name), List.of())); }
    @Override public List<String> addresses(String host) {
        lookups.add("A " + key(host));
        return List.copyOf(addresses.getOrDefault(key(host), defaultAddresses));
    }
    private Map<String, List<String>> records(Type type) {
        return switch (type) { case CNAME -> cnames; case TXT -> txt; case A -> addresses; };
    }
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
}

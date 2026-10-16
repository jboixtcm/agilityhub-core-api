package com.agilityhub.core.platform.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The local and test {@link DomainDns}: records a test publishes, nothing else (E10-T04: no real DNS from the tests). */
@Component @Profile("(local | test) & !staging & !prod")
public class FakeDomainDns implements DomainDns {
    private final Map<String, List<String>> cnames = new ConcurrentHashMap<>();
    private final Map<String, List<String>> txt = new ConcurrentHashMap<>();
    private final List<String> lookups = java.util.Collections.synchronizedList(new ArrayList<>());

    public void cname(String host, String target) { cnames.computeIfAbsent(key(host), ignored -> new ArrayList<>()).add(target); }
    public void txt(String name, String value) { txt.computeIfAbsent(key(name), ignored -> new ArrayList<>()).add(value); }
    public void clear() { cnames.clear(); txt.clear(); lookups.clear(); }
    /** `CNAME host` / `TXT name`, in the order they were asked. */
    public List<String> lookups() { return List.copyOf(lookups); }

    @Override public List<String> cnames(String host) { lookups.add("CNAME " + key(host)); return List.copyOf(cnames.getOrDefault(key(host), List.of())); }
    @Override public List<String> txt(String name) { lookups.add("TXT " + key(name)); return List.copyOf(txt.getOrDefault(key(name), List.of())); }
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
}

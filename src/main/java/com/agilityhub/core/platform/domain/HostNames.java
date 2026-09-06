package com.agilityhub.core.platform.domain;

import java.net.IDN;
import java.util.Locale;

public final class HostNames {
    private HostNames() { }
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) { return ""; }
        String host = raw.toLowerCase(Locale.ROOT);
        if (host.matches("[^:]+:[0-9]+")) { host = host.substring(0, host.lastIndexOf(':')); }
        if (host.endsWith(".")) { host = host.substring(0, host.length() - 1); }
        try { return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES); }
        catch (IllegalArgumentException invalid) { return ""; }
    }
}

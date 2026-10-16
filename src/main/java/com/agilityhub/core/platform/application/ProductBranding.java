package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.domain.Theme;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * S17 §2 «Entrada» (E118 #2, E10-T04): a product host (`clubsadmin.…`, `clubs.…`, `id.…`, the platform hosts of
 * `core.security.cors.platform-hosts`) resolves no tenant, and its `GET /branding` is the product's own: AgilityHub's theme
 * preset and languages (`platform/product-branding.json`, the template club's AgilityHub preset), so the console boots there.
 */
@Service
public class ProductBranding {
    public record Values(String name, List<String> locales, String defaultLocale, String timeZone, String currency, String countryProfile, Theme theme) { }
    private final List<String> hosts;
    private final Values values;
    private final String privacyPolicyUrl;
    public ProductBranding(@Value("${core.security.cors.platform-hosts}") List<String> hosts,
            @Value("${agilityhub.legal.privacyPolicyUrl}") String privacyPolicyUrl, ObjectMapper mapper) {
        this.hosts = hosts.stream().map(host -> host.strip().toLowerCase(Locale.ROOT)).filter(host -> !host.isEmpty()).toList();
        this.privacyPolicyUrl = privacyPolicyUrl;
        try (var input = ProductBranding.class.getResourceAsStream("/platform/product-branding.json")) {
            this.values = mapper.readValue(input, Values.class);
        } catch (IOException unreadable) { throw new UncheckedIOException(unreadable); }
    }
    /** Whether `host` (already normalized) is one of the product hosts. */
    public boolean productHost(String host) { return host != null && hosts.contains(host); }
    public Values values() { return values; }
    public String privacyPolicyUrl() { return privacyPolicyUrl; }
}

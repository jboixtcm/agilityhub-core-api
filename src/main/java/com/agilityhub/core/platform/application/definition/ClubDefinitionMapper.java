package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.PaymentProviderFlags;
import com.agilityhub.core.platform.domain.Pwa;
import com.agilityhub.core.platform.domain.Theme;
import com.agilityhub.core.platform.persistence.Club;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ClubDefinitionMapper {
    private final ObjectMapper mapper;
    public ClubDefinitionMapper(ObjectMapper mapper) { this.mapper = mapper; }

    /** Explicit projection prevents persistence metadata and provider secrets entering a diff/export. */
    public ObjectNode export(Club club) {
        ObjectNode root = mapper.createObjectNode(); root.put("apiVersion", "agilityhub.club/v1");
        ObjectNode identity = root.putObject("club");
        identity.put("slug", club.slug()); identity.put("name", club.name());
        identity.put("legalName", club.legalName()); identity.put("taxId", club.taxId());
        identity.set("address", mapper.valueToTree(club.address())); identity.put("displayCity", club.displayCity());
        identity.put("contactEmail", club.contactEmail()); identity.put("contactPhone", club.contactPhone());
        identity.put("websiteUrl", club.websiteUrl()); identity.set("locales", mapper.valueToTree(club.locales()));
        identity.put("defaultLocale", club.defaultLocale()); identity.put("timeZone", club.timeZone());
        identity.put("currency", club.currency()); identity.put("countryProfile", club.countryProfile());
        identity.put("status", club.status().name()); identity.put("template", club.template());
        var domains = root.putArray("domains");
        club.domains().forEach(domain -> domains.addObject().put("host", domain.host()).put("app", domain.app()).put("primary", domain.primary()));
        root.set("theme", mapper.valueToTree(club.theme())); root.set("pwa", mapper.valueToTree(club.pwa()));
        root.set("modules", mapper.valueToTree(club.modules().stream().map(Enum::name).sorted().toList()));
        // E3-T14: each provider with its switch only, in the configured order; its configuration and secrets never leave.
        // E5-T23: but cash's instructions, the club's public text of screen 19, so a definition can set them and round-trip.
        var providers = root.putObject("paymentProviders");
        club.paymentProviders().forEach((name, stored) -> {
            var provider = providers.putObject(name).put("enabled", PaymentProviderFlags.enabled(stored));
            var instructions = "MANUAL".equals(name) ? instructions(stored) : Map.<String, String>of();
            if (!instructions.isEmpty()) { provider.set("instructions", mapper.valueToTree(instructions)); }
        });
        root.set("legal", mapper.valueToTree(club.legal()));
        removeNulls(root);
        return root;
    }
    private void removeNulls(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            var names = new ArrayList<String>(); node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> { if (node.get(name).isNull()) { ((ObjectNode) node).remove(name); } else { removeNulls(node.get(name)); } });
        } else if (node.isArray()) { node.forEach(this::removeNulls); }
    }
    public Club merge(ObjectNode definition, Club previous, String id, Instant now, boolean trustedDomains) {
        ObjectNode merged = previous == null ? mapper.createObjectNode() : export(previous);
        ObjectNode identity = previous == null ? mapper.createObjectNode() : ((ObjectNode) merged.path("club")).deepCopy();
        identity.setAll((ObjectNode) definition.path("club")); merged.setAll(definition); merged.set("club", identity);
        var domains = new ArrayList<Club.Domain>();
        for (var domain : merged.path("domains")) {
            String host = domain.path("host").asText();
            var old = previous == null ? java.util.Optional.<Club.Domain>empty()
                    : previous.domains().stream().filter(item -> item.host().equals(host)).findFirst();
            var status = old.map(Club.Domain::status).orElse(trustedDomains ? Club.DomainStatus.VERIFIED : Club.DomainStatus.PENDING);
            Instant verified = old.map(Club.Domain::verifiedAt).orElse(status == Club.DomainStatus.VERIFIED ? now : null);
            domains.add(new Club.Domain(host, domain.path("app").asText("clubs"), status, verified, domain.path("primary").asBoolean()));
        }
        var providers = providers(definition, previous);
        Set<Module> modules = new java.util.HashSet<>(); merged.path("modules").forEach(module -> modules.add(Module.valueOf(module.asText())));
        List<String> locales = new ArrayList<>(); identity.path("locales").forEach(locale -> locales.add(locale.asText()));
        return new Club(id, identity.path("slug").asText(), identity.path("name").asText(), value(identity, "legalName"), value(identity, "taxId"),
                mapper.convertValue(identity.get("address"), Club.Address.class), value(identity, "displayCity"), value(identity, "contactEmail"), value(identity, "contactPhone"),
                value(identity, "websiteUrl"), locales, identity.path("defaultLocale").asText(), identity.path("timeZone").asText(),
                identity.path("currency").asText(), identity.path("countryProfile").asText(), domains,
                mapper.convertValue(merged.path("theme"), Theme.class), merged.has("pwa") ? mapper.convertValue(merged.get("pwa"), Pwa.class)
                        : new Pwa(identity.path("name").asText(), identity.path("name").asText(), List.of()), modules, providers,
                merged.has("legal") ? mapper.convertValue(merged.get("legal"), Club.Legal.class) : new Club.Legal("", Map.of(), ""),
                Club.Status.valueOf(identity.path("status").asText()), previous == null ? Map.of() : previous.onboardingChecklist(),
                previous == null ? Map.of() : previous.usage(), previous == null ? null : previous.version(),
                previous == null ? now : previous.createdAt(), now, identity.path("template").asBoolean(), previous == null ? null : previous.publicApiKeyHash());
    }
    /**
     * The declared providers, in the declared order, each keeping the configuration stored outside the file (S17 R-17-05).
     * A list of names keeps each stored `enabled` flag (a new one starts off); `{NAME: {enabled}}` sets it (E3-T14). A
     * definition without the section keeps the providers as stored.
     */
    private Map<String, Object> providers(ObjectNode definition, Club previous) {
        Map<String, Object> stored = previous == null ? Map.of() : previous.paymentProviders();
        if (!definition.has("paymentProviders")) { return stored; }
        Map<String, Object> providers = new LinkedHashMap<>();
        var declared = definition.get("paymentProviders");
        if (declared.isArray()) {
            declared.forEach(provider -> providers.put(provider.asText(), stored.getOrDefault(provider.asText(), Map.of())));
            return providers;
        }
        declared.fields().forEachRemaining(provider -> {
            var settings = stored.get(provider.getKey()) instanceof Map<?, ?> kept ? new LinkedHashMap<Object, Object>(kept) : new LinkedHashMap<Object, Object>();
            settings.put("enabled", provider.getValue().path("enabled").asBoolean());
            // E5-T23 (S04 §2 row 19): the schema takes `instructions` on MANUAL only; without them, the stored ones stay.
            if (provider.getValue().has("instructions")) { settings.put("instructions", mapper.convertValue(provider.getValue().get("instructions"), Map.class)); }
            providers.put(provider.getKey(), settings);
        });
        return providers;
    }
    /**
     * A stored provider's `instructions` by locale ({@code {values: …}} or the plain map, as `GET /signup` reads them), without
     * blank ones; empty when it has none (E5-T23).
     */
    static Map<String, String> instructions(Object stored) {
        if (!(stored instanceof Map<?, ?> settings)) { return Map.of(); }
        Object raw = settings.get("instructions");
        if (raw instanceof Map<?, ?> text && text.get("values") instanceof Map<?, ?> values) { raw = values; }
        var result = new LinkedHashMap<String, String>();
        if (raw instanceof Map<?, ?> values) {
            values.forEach((locale, value) -> { if (value instanceof String text && !text.isBlank()) { result.put(String.valueOf(locale), text); } });
        }
        return result;
    }
    private String value(ObjectNode object, String field) { return object.has(field) ? object.get(field).asText() : null; }
}

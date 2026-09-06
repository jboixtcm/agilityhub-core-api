package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.platform.application.Module;
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
        identity.set("address", mapper.valueToTree(club.address()));
        identity.put("contactEmail", club.contactEmail()); identity.put("contactPhone", club.contactPhone());
        identity.put("websiteUrl", club.websiteUrl()); identity.set("locales", mapper.valueToTree(club.locales()));
        identity.put("defaultLocale", club.defaultLocale()); identity.put("timeZone", club.timeZone());
        identity.put("currency", club.currency()); identity.put("countryProfile", club.countryProfile());
        identity.put("status", club.status().name()); identity.put("template", club.template());
        var domains = root.putArray("domains");
        club.domains().forEach(domain -> domains.addObject().put("host", domain.host()).put("app", domain.app()).put("primary", domain.primary()));
        root.set("theme", mapper.valueToTree(club.theme())); root.set("pwa", mapper.valueToTree(club.pwa()));
        root.set("modules", mapper.valueToTree(club.modules().stream().map(Enum::name).sorted().toList()));
        root.set("paymentProviders", mapper.valueToTree(club.paymentProviders().keySet().stream().sorted().toList()));
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
        Map<String, Object> providers = new LinkedHashMap<>();
        merged.path("paymentProviders").forEach(provider -> providers.put(provider.asText(), previous == null ? Map.of()
                : previous.paymentProviders().getOrDefault(provider.asText(), Map.of())));
        Set<Module> modules = new java.util.HashSet<>(); merged.path("modules").forEach(module -> modules.add(Module.valueOf(module.asText())));
        List<String> locales = new ArrayList<>(); identity.path("locales").forEach(locale -> locales.add(locale.asText()));
        return new Club(id, identity.path("slug").asText(), identity.path("name").asText(), value(identity, "legalName"), value(identity, "taxId"),
                mapper.convertValue(identity.get("address"), Club.Address.class), value(identity, "contactEmail"), value(identity, "contactPhone"),
                value(identity, "websiteUrl"), locales, identity.path("defaultLocale").asText(), identity.path("timeZone").asText(),
                identity.path("currency").asText(), identity.path("countryProfile").asText(), domains,
                mapper.convertValue(merged.path("theme"), Theme.class), merged.has("pwa") ? mapper.convertValue(merged.get("pwa"), Pwa.class)
                        : new Pwa(identity.path("name").asText(), identity.path("name").asText(), List.of()), modules, providers,
                merged.has("legal") ? mapper.convertValue(merged.get("legal"), Club.Legal.class) : new Club.Legal("", Map.of(), ""),
                Club.Status.valueOf(identity.path("status").asText()), previous == null ? Map.of() : previous.onboardingChecklist(),
                previous == null ? Map.of() : previous.usage(), previous == null ? null : previous.version(),
                previous == null ? now : previous.createdAt(), now, identity.path("template").asBoolean());
    }
    private String value(ObjectNode object, String field) { return object.has(field) ? object.get(field).asText() : null; }
}

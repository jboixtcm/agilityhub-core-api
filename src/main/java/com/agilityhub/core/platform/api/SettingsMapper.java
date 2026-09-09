package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.audit.AuditQuery;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.shared.application.contract.ApiContracts.LastChange;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.platform.api.SettingsContracts.*;

@Component
public class SettingsMapper {
    private final ParameterSettingsService parameters;
    private final AuditQuery audit;
    public SettingsMapper(ParameterSettingsService parameters, AuditQuery audit) { this.parameters = parameters; this.audit = audit; }

    public Parameter parameter(ParameterSettingsService.View view) {
        var d = view.definition();
        return new Parameter(d.key(), d.type(), d.labelKey(), d.helpKey(), view.value(), view.isOverride(), view.scopeRef(),
                lastChange("Parameter", view.id()), ParameterEditor.valueOf(d.editableBy()), d.constraints(),
                d.modules().isEmpty() ? null : d.modules().getFirst().name(), view.version(), parameters.defaultValue(d),
                d.block(), view.history().stream().map(h -> new ParameterHistoryEntry(h.value(), h.changedAt(), h.changedByAccountId(), h.reason())).toList());
    }
    public Parameters parameters(List<ParameterSettingsService.View> values) {
        var blocks = new LinkedHashMap<String, List<Parameter>>();
        values.forEach(view -> blocks.computeIfAbsent(view.definition().block(), ignored -> new ArrayList<>()).add(parameter(view)));
        var last = blocks.values().stream().flatMap(List::stream).map(Parameter::lastChange).filter(Objects::nonNull)
                .max(Comparator.comparing(LastChange::at)).orElse(null);
        return new Parameters(blocks.entrySet().stream().map(e -> new ParameterBlock(e.getKey(),
                "admin-settings:block." + e.getKey() + ".title", e.getValue())).toList(), last);
    }
    public ParameterDefinition definition(com.agilityhub.core.platform.domain.ParameterDefinition d) {
        return new ParameterDefinition(d.key(), d.type(), d.defaultValue(), d.block(), d.labelKey(), d.helpKey(), d.constraints(),
                d.modules().stream().map(Enum::name).toList(), d.scope(), ParameterEditor.valueOf(d.editableBy()), d.restartRequired());
    }
    public ClubSettings club(Club c) {
        var address = c.address();
        var providers = new LinkedHashMap<String, PaymentProviderSummary>();
        c.paymentProviders().forEach((key, value) -> {
            Map<?, ?> config = value instanceof Map<?, ?> map ? map : Map.of();
            boolean configured = config.entrySet().stream().anyMatch(e -> !e.getKey().equals("enabled") && e.getValue() != null);
            providers.put(key, new PaymentProviderSummary(configured, Boolean.TRUE.equals(config.get("enabled"))));
        });
        var legal = c.legal();
        var pwa = c.pwa();
        var icons = new LinkedHashMap<String, String>();
        if (pwa != null) { pwa.icons().forEach(icon -> icons.put(icon.sizes(), icon.src())); }
        return new ClubSettings(c.id(), c.slug(), c.name(), c.legalName(), c.taxId(), address == null ? null :
                new ClubAddress(address.street(), address.postalCode(), address.city(), address.region(), address.country()),
                c.contactEmail(), c.contactPhone(), c.websiteUrl(), c.locales(), c.defaultLocale(), c.timeZone(), c.currency(),
                c.countryProfile(), c.domains().stream().map(d -> new ClubDomain(d.host(), d.app(), d.verifiedAt(), d.primary())).toList(),
                c.theme(), pwa == null ? null : new ClubPwa(pwa.name(), pwa.shortName(), icons),
                c.modules().stream().map(Enum::name).sorted().toList(), providers,
                legal == null ? null : new ClubLegal(legal.privacyPolicyUrl(), legal.imageConsentText().get(c.defaultLocale()),
                        legal.imageConsentText(), legal.legalTextsVersion()), c.status().name(), lastChange("Club", c.id()), c.version());
    }
    private LastChange lastChange(String type, String id) {
        var last = audit.lastChange(type, id);
        return last == null ? null : new LastChange(last.at(), last.actorName(), last.action().name());
    }
}

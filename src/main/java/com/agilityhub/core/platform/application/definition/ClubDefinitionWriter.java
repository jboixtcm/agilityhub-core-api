package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.platform.application.ClubAdminProvisioner;
import com.agilityhub.core.platform.application.ClubAccountProvisioner;
import com.agilityhub.core.platform.application.ParameterCatalog;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.platform.domain.ParameterValidator;
import com.agilityhub.core.platform.domain.events.ParameterChanged;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.persistence.ParameterRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.audit.AuditField;
import com.agilityhub.core.shared.domain.events.ClubConfigChanged;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClubDefinitionWriter {
    private final ClubRepository clubs;
    private final ParameterRepository parameters;
    private final ParameterCatalog catalog;
    private final ClubAdminProvisioner admins;
    private final ClubAccountProvisioner accounts;
    private final com.agilityhub.core.platform.application.ClubPageProvisioner pages;
    private final com.agilityhub.core.platform.application.ClubCatalogProvisioner catalogs;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final ClubDefinitionMapper definitions;
    private final Clock clock;
    private final boolean trustedDomains;
    public ClubDefinitionWriter(ClubRepository clubs, ParameterRepository parameters, ParameterCatalog catalog,
                                ClubAdminProvisioner admins, ClubAccountProvisioner accounts, EventPublisher events, ObjectMapper mapper,
                                ClubDefinitionMapper definitions, Clock clock, Environment environment, com.agilityhub.core.platform.application.ClubPageProvisioner pages, com.agilityhub.core.platform.application.ClubCatalogProvisioner catalogs) {
        this.clubs = clubs; this.parameters = parameters; this.catalog = catalog; this.admins = admins;
        this.accounts = accounts; this.pages = pages; this.catalogs = catalogs;
        this.events = events; this.mapper = mapper; this.definitions = definitions; this.clock = clock;
        trustedDomains = environment.acceptsProfiles(Profiles.of("local", "test")) && !environment.acceptsProfiles(Profiles.of("staging", "prod"));
    }
    public record Result(String id, List<String> lines, @AuditField Map<String, Object> summary) {
        public int changes() { return summary.size(); }
        public String render(boolean dryRun) {
            return String.join("\n", lines) + "\n" + changes() + " changes" + (dryRun ? " (dry run)" : " (applied)");
        }
    }
    private record Plan(Club club, List<Parameter> parameters, List<ClubAdminProvisioner.Admin> admins,
                        List<ClubAccountProvisioner.SeedAccount> accounts, List<com.agilityhub.core.platform.application.ClubPageProvisioner.Page> pages, List<com.agilityhub.core.platform.application.ClubCatalogProvisioner.Change> catalogs, Result result) { }

    public Result preview(ObjectNode definition) { return preview(definition, false, false); }
    public Result preview(ObjectNode definition, boolean allowSeedPasswords, boolean accountsOnly) {
        return plan(definition, allowSeedPasswords, accountsOnly).result();
    }

    @Transactional
    @Audited(action = AuditAction.CLUB_UPDATED, entityType = "'Club'", entity = "#result.id",
            reason = "#result.changes() == 0 ? null : 'source: APPLY'")
    public Result apply(ObjectNode definition, boolean allowSeedPasswords, boolean accountsOnly) {
        Plan plan = plan(definition, allowSeedPasswords, accountsOnly);
        if (plan.result().changes() == 0) { return plan.result(); }
        // Saving the club also serializes concurrent parameter/admin changes through its version.
        clubs.save(plan.club());
        for (var parameter : plan.parameters()) {
            if (parameter.version() == null) { parameters.insert(parameter); } else { parameters.replace(parameter); }
            var payload = new LinkedHashMap<String, Object>();
            payload.put("key", parameter.key()); payload.put("after", parameter.value());
            payload.put("before", parameter.history().getLast().value()); payload.put("reason", "source: APPLY");
            events.publish(new ParameterChanged(plan.club().id(), clock.instant(), payload, null, null, DomainEvent.Origin.SYSTEM));
        }
        plan.admins().forEach(admins::provision);
        plan.accounts().forEach(account -> accounts.provision(account, allowSeedPasswords));
        catalogs.provision(plan.catalogs());
        plan.pages().forEach(page -> pages.provision(page, plan.club().defaultLocale(), plan.club().locales()));
        events.publish(new ClubConfigChanged(plan.club().id(), clock.instant(), Map.of("diff", plan.result().summary()),
                null, null, DomainEvent.Origin.SYSTEM));
        return plan.result();
    }
    private Plan plan(ObjectNode definition, boolean allowSeedPasswords, boolean accountsOnly) {
        String id = TenantContext.require();
        Club old = clubs.findBySlug(definition.path("club").path("slug").asText()).orElse(null);
        if (old != null && !old.id().equals(id)) { throw new ApiException(ErrorCode.STALE_VERSION); }
        if (accountsOnly) {
            if (old == null) { throw new ApiException(ErrorCode.CLUB_NOT_FOUND); }
            var lines = new ArrayList<String>(); var summary = new LinkedHashMap<String, Object>();
            var changedAccounts = planAccounts(definition, allowSeedPasswords, lines, summary);
            return new Plan(old, List.of(), List.of(), changedAccounts, List.of(), List.of(), new Result(id, List.copyOf(lines), Map.copyOf(summary)));
        }
        Club next = definitions.merge(definition, old, id, clock.instant(), trustedDomains);
        for (var domain : next.domains()) {
            clubs.findByAnyHost(domain.host()).filter(owner -> !owner.id().equals(id)).ifPresent(owner -> {
                throw new ApiException(ErrorCode.HOST_ALREADY_USED, Map.of("host", domain.host()));
            });
        }
        var lines = new ArrayList<String>(); var summary = new LinkedHashMap<String, Object>();
        ObjectNode before = old == null ? mapper.createObjectNode() : definitions.export(old);
        ObjectNode after = definitions.export(next);
        for (String section : List.of("club", "domains", "theme", "modules", "paymentProviders", "legal", "pwa")) {
            diff(section, before.get(section), after.get(section), lines, summary);
        }
        Map<String, Parameter> stored = parameters.findAll().stream().filter(parameter -> parameter.scopeRef() == null)
                .collect(Collectors.toMap(Parameter::key, parameter -> parameter));
        var changed = new ArrayList<Parameter>();
        definition.path("parameters").fields().forEachRemaining(entry -> {
            var parameterDefinition = catalog.get(entry.getKey()); Object value = mapper.convertValue(entry.getValue(), Object.class);
            new ParameterValidator().validate(parameterDefinition, value, next.defaultLocale(), next.currency());
            var previous = stored.get(entry.getKey());
            if (previous == null || !mapper.valueToTree(previous.value()).equals(entry.getValue())) {
                var history = previous == null ? new ArrayList<Parameter.History>() : new ArrayList<>(previous.history());
                history.add(new Parameter.History(previous == null ? null : previous.value(), clock.instant(), null, "source: APPLY"));
                changed.add(new Parameter(previous == null ? UUID.randomUUID().toString() : previous.id(), id, entry.getKey(), value,
                        parameterDefinition.type(), "club", null, history, previous == null ? null : previous.version() + 1, clock.instant()));
                diff("parameters." + entry.getKey(), previous == null ? null : mapper.valueToTree(previous.value()), entry.getValue(), lines, summary);
            } else { lines.add("= parameters." + entry.getKey() + " unchanged"); }
        });
        if (definition.path("parameters").isEmpty()) { lines.add((old == null ? "+" : "=") + " parameters: no declared overrides"); }
        var newAdmins = new ArrayList<ClubAdminProvisioner.Admin>();
        for (var entry : definition.path("admins")) {
            var admin = mapper.convertValue(entry, ClubAdminProvisioner.Admin.class);
            if (admins.needsProvision(admin)) { newAdmins.add(admin); }
        }
        lines.add((newAdmins.isEmpty() ? old == null ? "+" : "=" : "+") + " admins: " + newAdmins.size() + " to provision");
        if (!newAdmins.isEmpty()) { summary.put("admins", newAdmins.size()); }
        var changedAccounts = planAccounts(definition, allowSeedPasswords, lines, summary);
        var changedCatalogs = catalogs.plan(definition.path("catalogs"));
        for (var change : changedCatalogs) { lines.add((change.id() == null ? "+ " : "~ ") + "catalogs." + change.section() + "." + change.key()); }
        lines.add((changedCatalogs.isEmpty() ? "=" : "+") + " catalogs: " + changedCatalogs.size() + " catalog changes");
        if (!changedCatalogs.isEmpty()) { summary.put("catalogs", changedCatalogs.size()); }
        lines.add((old == null ? "+" : "=") + " messageTemplates: schema accepted; application deferred to E7");
        var changedPages = new ArrayList<com.agilityhub.core.platform.application.ClubPageProvisioner.Page>();
        var pageKeys = new java.util.HashSet<String>();
        for (var entry : definition.path("pages")) {
            var page = mapper.convertValue(entry, com.agilityhub.core.platform.application.ClubPageProvisioner.Page.class);
            if (!pageKeys.add(page.key())) { throw new ApiException(ErrorCode.DUPLICATE_NAME, Map.of("field", "key")); }
            pages.validate(page, next.defaultLocale(), next.locales());
            if (pages.needsProvision(page)) { changedPages.add(page); }
        }
        lines.add((changedPages.isEmpty() ? "=" : "+") + " pages: " + changedPages.size() + " page changes");
        if (!changedPages.isEmpty()) { summary.put("pages", changedPages.size()); }
        return new Plan(next, changed, newAdmins, changedAccounts, changedPages, changedCatalogs, new Result(id, List.copyOf(lines), Map.copyOf(summary)));
    }
    private List<ClubAccountProvisioner.SeedAccount> planAccounts(ObjectNode definition, boolean allowSeedPasswords,
                                                                 List<String> lines, Map<String, Object> summary) {
        var changed = new ArrayList<ClubAccountProvisioner.SeedAccount>();
        for (var entry : definition.path("accounts")) {
            var account = mapper.convertValue(entry, ClubAccountProvisioner.SeedAccount.class);
            accounts.validate(account, allowSeedPasswords);
            if (accounts.needsProvision(account)) { changed.add(account); }
        }
        lines.add((changed.isEmpty() ? "=" : "+") + " accounts: " + changed.size() + " to provision");
        if (!changed.isEmpty()) { summary.put("accounts", changed.size()); }
        return List.copyOf(changed);
    }
    private void diff(String path, JsonNode before, JsonNode after, List<String> lines, Map<String, Object> summary) {
        if (Objects.equals(before, after)) { lines.add("= " + path + " unchanged"); return; }
        summary.put(path.replace('.', '/'), before == null ? "added" : "changed");
        lines.add((before == null ? "+ " : "~ ") + path + " " + (before == null ? "added" : "changed"));
        if (before != null && before.isObject() && after.isObject()) {
            var keys = new java.util.TreeSet<String>(); before.fieldNames().forEachRemaining(keys::add); after.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                if (!Objects.equals(before.get(key), after.get(key))) { lines.add("  " + path + "." + key + ": " + before.get(key) + " -> " + after.get(key)); }
            }
        } else { lines.add("  " + before + " -> " + after); }
    }
}

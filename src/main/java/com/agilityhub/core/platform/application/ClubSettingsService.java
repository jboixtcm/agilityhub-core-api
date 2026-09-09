package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.domain.events.ClubModulesChanged;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.audit.AuditField;
import com.agilityhub.core.shared.domain.events.ClubConfigChanged;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.DataAccessException;
import com.agilityhub.core.platform.persistence.SettingsWriteConflict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClubSettingsService {
    private static final Set<String> EDITABLE = Set.of("name", "legalName", "taxId", "address", "contactEmail", "contactPhone", "websiteUrl", "theme");
    private static final Set<String> PLATFORM = Set.of("id", "slug", "locales", "defaultLocale", "timeZone", "currency", "countryProfile",
            "domains", "modules", "paymentProviders", "legal", "pwa", "status", "onboardingChecklist", "usage", "template", "createdAt", "updatedAt");
    private final ClubRepository clubs;
    private final ClubConfigService configs;
    private final ModuleDependencyValidator modules;
    private final ClubScheduleAccess schedule;
    private final EventPublisher events;
    private final AuditActorProvider actors;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ClubSettingsService(ClubRepository clubs, ClubConfigService configs, ModuleDependencyValidator modules,
            ClubScheduleAccess schedule, EventPublisher events, AuditActorProvider actors, ObjectMapper mapper, Clock clock) {
        this.clubs = clubs; this.configs = configs; this.modules = modules; this.schedule = schedule;
        this.events = events; this.actors = actors; this.mapper = mapper; this.clock = clock;
    }
    public Club club() { return clubs.findById(TenantContext.require()).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND)); }
    public record State(String id, @AuditField Map<String, Object> settings) { }
    public State snapshot() {
        var club = club();
        Map<String, Object> values = mapper.convertValue(club, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        values.keySet().retainAll(EDITABLE);
        // taxId is an organisation identifier; no payment credentials enter this allowlist.
        return new State(club.id(), values);
    }
    public record ModulesState(String id, @AuditField List<String> modules) { }
    public ModulesState modulesSnapshot() { var club = club(); return new ModulesState(club.id(), moduleNames(club.modules())); }
    public List<String> moduleNames(Set<Module> enabled) { return enabled.stream().map(Enum::name).sorted().toList(); }

    @Transactional
    @Audited(action = AuditAction.CLUB_UPDATED, entityType = "'Club'", entity = "#result.id", before = "snapshot()")
    public State update(Map<String, Object> request) {
        var before = club();
        if (request.containsKey("timeZone") && !Objects.equals(request.get("timeZone"), before.timeZone()) && schedule.hasClasses()) {
            throw new ApiException(ErrorCode.TIMEZONE_CHANGE_BLOCKED);
        }
        if (request.keySet().stream().anyMatch(PLATFORM::contains)) { throw new ApiException(ErrorCode.PLATFORM_ONLY); }
        if (request.keySet().stream().anyMatch(key -> !key.equals("version") && !EDITABLE.contains(key))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        if (!(request.get("version") instanceof Number version) || version.longValue() != before.version()
                || version.doubleValue() != version.longValue()) { throw new ApiException(ErrorCode.STALE_VERSION); }
        ObjectNode tree = mapper.valueToTree(before);
        request.forEach((key, value) -> { if (EDITABLE.contains(key)) { tree.set(key, mapper.valueToTree(value)); } });
        Club next;
        try {
            validate(tree);
            tree.set("updatedAt", mapper.valueToTree(clock.instant()));
            next = mapper.convertValue(tree, Club.class);
        } catch (IllegalArgumentException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        var oldState = snapshot();
        save(next);
        var newState = snapshot();
        events.publish(new ClubConfigChanged(before.id(), clock.instant(), Map.of("diff",
                Map.of("before", oldState.settings(), "after", newState.settings())), actors.current().accountId(), null, DomainEvent.Origin.BACKOFFICE));
        configs.invalidateAfterCommit(before.id());
        return newState;
    }

    @Transactional
    @Audited(action = AuditAction.CLUB_MODULES_CHANGED, entityType = "'Club'", entity = "#result.id", before = "modulesSnapshot()")
    public ModulesState updateModule(String key, boolean enabled) {
        Module module;
        try { module = Module.valueOf(key); }
        catch (IllegalArgumentException invalid) { throw new ApiException(ErrorCode.PLATFORM_ONLY); }
        if (!module.selfService()) { throw new ApiException(ErrorCode.PLATFORM_ONLY); }
        var club = club();
        modules.validateChange(club.modules(), module, enabled);
        if (club.modules().contains(module) == enabled) { return modulesSnapshot(); }
        var proposed = EnumSet.noneOf(Module.class); proposed.addAll(club.modules());
        if (enabled) { proposed.add(module); } else { proposed.remove(module); }
        ObjectNode tree = mapper.valueToTree(club);
        tree.set("modules", mapper.valueToTree(proposed)); tree.set("updatedAt", mapper.valueToTree(clock.instant()));
        save(mapper.convertValue(tree, Club.class));
        var result = modulesSnapshot();
        events.publish(new ClubModulesChanged(club.id(), clock.instant(), Map.of("diff", Map.of("modules",
                Map.of("before", moduleNames(club.modules()), "after", result.modules()))),
                actors.current().accountId(), null, DomainEvent.Origin.BACKOFFICE));
        configs.invalidateAfterCommit(club.id());
        return result;
    }
    private void save(Club club) {
        try { clubs.save(club); }
        catch (DataAccessException failure) { throw SettingsWriteConflict.translate(failure); }
    }
    private void validate(ObjectNode club) {
        for (String key : List.of("name", "legalName", "taxId", "contactEmail", "contactPhone", "websiteUrl")) {
            var value = club.path(key);
            if (!value.isNull() && !value.isTextual()) { throw new IllegalArgumentException("Expected text"); }
        }
        var email = club.path("contactEmail");
        if (email.isTextual() && !email.asText().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) { throw new IllegalArgumentException("Invalid email"); }
        var theme = club.path("theme");
        for (String key : List.of("colors", "fontFamily", "radius", "ringPalette", "mode")) {
            if (!theme.hasNonNull(key)) { throw new IllegalArgumentException("Incomplete theme"); }
        }
        var fields = mapper.valueToTree(club().theme().colors()).fieldNames();
        while (fields.hasNext()) {
            if (!theme.path("colors").path(fields.next()).asText().matches("#[a-fA-F0-9]{6}")) { throw new IllegalArgumentException("Invalid color"); }
        }
    }
}

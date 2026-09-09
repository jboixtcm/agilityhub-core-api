package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.domain.ParameterDefinition;
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
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParameterSettingsService {
    private final ParameterCatalog catalog;
    private final ParameterRepository parameters;
    private final ClubRepository clubs;
    private final ClubConfigService configs;
    private final EventPublisher events;
    private final AuditActorProvider actors;
    private final Clock clock;
    private final ParameterValidator validator = new ParameterValidator();

    public ParameterSettingsService(ParameterCatalog catalog, ParameterRepository parameters, ClubRepository clubs,
            ClubConfigService configs, EventPublisher events, AuditActorProvider actors, Clock clock) {
        this.catalog = catalog; this.parameters = parameters; this.clubs = clubs; this.configs = configs;
        this.events = events; this.actors = actors; this.clock = clock;
    }

    public record View(String id, ParameterDefinition definition, @AuditField Object value,
            @AuditField boolean isOverride, String scopeRef, long version, List<Parameter.History> history) { }

    public List<View> list(String block) {
        return catalog.entries().values().stream().filter(d -> block == null || block.equals(d.block()))
                .map(d -> view(d.key(), null)).toList();
    }

    public View view(String key, String scopeRef) {
        var definition = catalog.get(key);
        checkScope(definition, scopeRef);
        var club = club();
        var stored = parameters.findByKey(key, scopeRef).orElse(null);
        Object value = validValue(definition, stored, club);
        boolean isOverride = value != null;
        if (value == null && scopeRef != null) { value = validValue(definition, parameters.findByKey(key, null).orElse(null), club); }
        if (value == null) { value = defaultValue(definition, club); }
        return new View(stored == null ? key : stored.id(), definition, value, isOverride, scopeRef,
                stored == null ? 0 : stored.version(), stored == null ? List.of() : stored.history());
    }

    public Object defaultValue(ParameterDefinition definition) { return defaultValue(definition, club()); }
    private Object defaultValue(ParameterDefinition definition, Club club) {
        if (definition.type().equals("money") && definition.defaultValue() instanceof Map<?, ?> money) {
            return Map.of("amountMinor", money.get("amountMinor"), "currency", club.currency());
        }
        return definition.defaultValue();
    }
    private Object validValue(ParameterDefinition definition, Parameter stored, Club club) {
        if (stored == null || stored.value() == null) { return null; }
        try { validator.validate(definition, stored.value(), club.defaultLocale(), club.currency()); return stored.value(); }
        catch (ApiException invalid) { return null; }
    }

    @Transactional
    @Audited(action = AuditAction.PARAMETER_CHANGED, entityType = "'Parameter'", entity = "#result.id",
            before = "view(#key, #scopeRef)", reason = "#reason")
    public View update(String key, Object value, String scopeRef, Long version, String reason) {
        var definition = editable(key, scopeRef);
        var club = club();
        validator.validate(definition, value, club.defaultLocale(), club.currency());
        var old = parameters.findByKey(key, scopeRef).orElse(null);
        if (version == null || version != (old == null ? 0 : old.version())) { throw new ApiException(ErrorCode.STALE_VERSION); }
        return write(definition, old, value, scopeRef, reason);
    }

    @Transactional
    @Audited(action = AuditAction.PARAMETER_CHANGED, entityType = "'Parameter'", entity = "#result.id",
            before = "view(#key, #scopeRef)")
    public View reset(String key, String scopeRef) {
        var definition = editable(key, scopeRef);
        var old = parameters.findByKey(key, scopeRef).orElse(null);
        if (old == null || old.value() == null) { return view(key, scopeRef); }
        return write(definition, old, null, scopeRef, null);
    }

    private View write(ParameterDefinition definition, Parameter old, Object value, String scopeRef, String reason) {
        var before = view(definition.key(), scopeRef);
        var history = new ArrayList<>(before.history());
        var actor = actors.current();
        history.add(new Parameter.History(before.value(), clock.instant(), actor.accountId(), reason));
        var saved = new Parameter(old == null ? UUID.randomUUID().toString() : old.id(), TenantContext.require(),
                definition.key(), value, definition.type(), scopeRef == null ? "club" : definition.scope(), scopeRef,
                history, old == null ? 1L : old.version() + 1, clock.instant());
        parameters.saveVersioned(saved, old == null ? null : old.version());
        var after = view(definition.key(), scopeRef);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("key", definition.key()); payload.put("before", before.value()); payload.put("after", after.value());
        payload.put("scopeRef", scopeRef);
        events.publish(new ParameterChanged(TenantContext.require(), clock.instant(), payload, actor.accountId(), null,
                DomainEvent.Origin.BACKOFFICE));
        configs.invalidateAfterCommit(TenantContext.require());
        return after;
    }

    private ParameterDefinition editable(String key, String scopeRef) {
        var definition = catalog.get(key);
        if (!definition.editableBy().equals("CLUB")) { throw new ApiException(ErrorCode.PLATFORM_ONLY); }
        checkScope(definition, scopeRef);
        if (!club().modules().containsAll(definition.modules())) { throw new ApiException(ErrorCode.MODULE_DISABLED); }
        return definition;
    }
    private void checkScope(ParameterDefinition definition, String scopeRef) {
        if (scopeRef != null && (scopeRef.isBlank() || !Set.of("ring", "level").contains(definition.scope()))) {
            throw new ApiException(ErrorCode.PARAMETER_INVALID, Map.of("key", definition.key()));
        }
    }
    private Club club() { return clubs.findById(TenantContext.require()).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND)); }
}

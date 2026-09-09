package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.*;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoException;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private final Map<CatalogKind, CatalogRepository<? extends CatalogEntity>> repositories;
    private final ClubConfigService configs;
    private final UsageCounter usage;
    private final AuditActorProvider actors;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CatalogService(CatalogRepository<Level> levels, CatalogRepository<Ring> rings, CatalogRepository<FaqEntry> faqs,
            ClubConfigService configs, UsageCounter usage, AuditActorProvider actors, EventPublisher events, ObjectMapper mapper, Clock clock) {
        repositories = Map.of(CatalogKind.LEVEL, levels, CatalogKind.RING, rings, CatalogKind.FAQ, faqs);
        this.configs = configs; this.usage = usage; this.actors = actors; this.events = events; this.mapper = mapper; this.clock = clock;
    }
    public ClubConfig config() { return configs.get(TenantContext.require()); }
    @SuppressWarnings("unchecked")
    private CatalogRepository<CatalogEntity> repository(CatalogKind kind) {
        return (CatalogRepository<CatalogEntity>) repositories.get(kind);
    }
    public List<CatalogEntity> list(CatalogKind kind, boolean includeInactive) {
        return repository(kind).findAll().stream().filter(item -> includeInactive || item.active())
                .sorted(Comparator.comparingInt(CatalogEntity::order).thenComparing(CatalogEntity::id)).toList();
    }
    public CatalogEntity get(CatalogKind kind, String id) {
        return repository(kind).findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
    public Map<String, Long> usage(CatalogKind kind, String id) { return usage.usage(kind, id); }
    public Map<String, Object> snapshot(CatalogKind kind, String id) { return fields(get(kind, id)); }
    public Map<String, Object> orderSnapshot(CatalogKind kind) {
        Map<String, Object> order = new LinkedHashMap<>();
        list(kind, true).forEach(item -> order.put(item.id(), item.order()));
        return order;
    }

    @Transactional
    @Audited(action = AuditAction.CATALOG_CHANGED, entityType = "#kind.entityType()", entity = "#result['id']")
    public Map<String, Object> create(CatalogKind kind, Map<String, Object> request) {
        return write(() -> {
            repository(kind).lock();
            var current = list(kind, true);
            var values = new LinkedHashMap<>(request);
            values.putIfAbsent("order", current.stream().mapToInt(CatalogEntity::order).max().orElse(-10) + 10);
            values.putIfAbsent("active", true);
            if (kind != CatalogKind.FAQ) {
                var palette = config().ringPalette();
                var used = current.stream().map(item -> fields(item).get("color")).toList();
                values.putIfAbsent("color", palette.stream().filter(color -> !used.contains(color)).findFirst()
                        .orElseGet(() -> palette.isEmpty() ? config().primaryColor() : palette.get(current.size() % palette.size())));
            }
            if (kind == CatalogKind.LEVEL) {
                values.putIfAbsent("capacity", config().get("classes.defaultCapacity", Integer.class));
                values.putIfAbsent("grantsFreeTraining", false);
            }
            if (kind == CatalogKind.RING) { values.putIfAbsent("allowsFreeTraining", false); }
            var next = build(kind, UUID.randomUUID().toString(), values, null);
            unique(kind, next, current);
            repository(kind).insert(next);
            publish(kind, null, next, "CREATED");
            return fields(next);
        });
    }

    @Transactional
    @Audited(action = AuditAction.CATALOG_CHANGED, entityType = "#kind.entityType()", entity = "#id", before = "snapshot(#kind, #id)")
    public Map<String, Object> update(CatalogKind kind, String id, Map<String, Object> patch) {
        return write(() -> {
            repository(kind).lock();
            var before = get(kind, id);
            if (!(patch.get("version") instanceof Number version) || version.longValue() != before.version()) {
                throw new ApiException(ErrorCode.STALE_VERSION);
            }
            var values = new LinkedHashMap<>(fields(before)); values.putAll(patch);
            var next = build(kind, id, values, before);
            unique(kind, next, list(kind, true));
            if (before instanceof Ring oldRing && next instanceof Ring ring) {
                var references = usage(kind, id);
                boolean deactivating = oldRing.active() && !ring.active();
                boolean disabling = oldRing.allowsFreeTraining() && !ring.allowsFreeTraining();
                if ((deactivating && references.get("futureClassSessions") > 0)
                        || ((deactivating || disabling) && references.get("futureTrainingBookings") > 0)) {
                    throw new ApiException(ErrorCode.RING_IN_USE, new LinkedHashMap<>(references));
                }
            }
            repository(kind).update(next, before.version());
            String action = before.active() == next.active() ? "UPDATED" : next.active() ? "REACTIVATED" : "DEACTIVATED";
            publish(kind, before, next, action);
            return fields(next);
        });
    }

    @Transactional
    @Audited(action = AuditAction.CATALOG_CHANGED, entityType = "#kind.entityType()", entity = "#id", before = "snapshot(#kind, #id)")
    public void delete(CatalogKind kind, String id) {
        write(() -> {
            repository(kind).lock();
            var before = get(kind, id);
            if (usage.hasReferences(kind, id)) {
                throw new ApiException(kind == CatalogKind.LEVEL ? ErrorCode.LEVEL_IN_USE : ErrorCode.RING_IN_USE,
                        new LinkedHashMap<>(usage(kind, id)));
            }
            repository(kind).delete(before); publish(kind, before, null, "DELETED");
            return null;
        });
    }

    @Transactional
    @Audited(action = AuditAction.CATALOG_CHANGED, entityType = "#kind.entityType()", entity = "'order'", before = "orderSnapshot(#kind)")
    public Map<String, Object> order(CatalogKind kind, List<String> ids) {
        return write(() -> {
            repository(kind).lock();
            var current = list(kind, true);
            if (ids.size() != current.size() || new HashSet<>(ids).size() != ids.size()
                    || !new HashSet<>(ids).equals(new HashSet<>(current.stream().map(CatalogEntity::id).toList()))) {
                throw new ApiException(ErrorCode.ORDER_INCOMPLETE);
            }
            for (var before : current) {
                int position = ids.indexOf(before.id()) * 10;
                if (position == before.order()) { continue; }
                var values = new LinkedHashMap<>(fields(before)); values.put("order", position);
                var next = build(kind, before.id(), values, before);
                repository(kind).update(next, before.version()); publish(kind, before, next, "REORDERED");
            }
            return orderSnapshot(kind);
        });
    }

    private CatalogEntity build(CatalogKind kind, String id, Map<String, Object> values, CatalogEntity before) {
        var now = clock.instant(); var actor = actors.current().accountId(); var clubId = TenantContext.require();
        var createdAt = before == null ? now : before.createdAt();
        var createdBy = before == null ? actor : before.createdByAccountId();
        long version = before == null ? 0 : before.version() + 1;
        int order = ((Number) values.get("order")).intValue(); boolean active = (Boolean) values.get("active");
        return switch (kind) {
            case LEVEL -> new Level(id, clubId, ((String) values.get("code")).toUpperCase(Locale.ROOT),
                    localized(values.get("name"), "name", 40), order, (String) values.get("color"), ((Number) values.get("capacity")).intValue(),
                    (Boolean) values.get("grantsFreeTraining"), active, version, createdAt, now, createdBy, actor);
            case RING -> new Ring(id, clubId, requiredText(values.get("name"), "name", 40), ((String) values.get("shortName")).toUpperCase(Locale.ROOT),
                    (String) values.get("color"), (Boolean) values.get("allowsFreeTraining"), (Integer) values.get("trainingCapacity"),
                    order, active, version, createdAt, now, createdBy, actor);
            case FAQ -> new FaqEntry(id, clubId, localized(values.get("category"), "category", 60), localized(values.get("question"), "question", 200),
                    localized(values.get("answer"), "answer", 2000), order, active, version, createdAt, now, createdBy, actor);
        };
    }
    private LocalizedText localized(Object raw, String field, int max) {
        if (!(raw instanceof Map<?, ?> values) || !values.containsKey(config().club().defaultLocale())) { throw invalid(field, "VALIDATION_ERROR"); }
        Map<String, String> text = new LinkedHashMap<>();
        values.forEach((locale, value) -> {
            if (!config().club().locales().contains(locale)) { throw invalid(field, "LOCALE_NOT_ENABLED"); }
            text.put((String) locale, requiredText(value, field, max));
        });
        return new LocalizedText(text, config().club().defaultLocale());
    }
    private String requiredText(Object raw, String field, int max) {
        if (!(raw instanceof String text) || text.isBlank() || text.length() > max) { throw invalid(field, "VALIDATION_ERROR"); }
        return text.strip();
    }
    private ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field", field, "code", code))));
    }
    private void unique(CatalogKind kind, CatalogEntity next, List<CatalogEntity> existing) {
        for (var other : existing) {
            if (other.id().equals(next.id())) { continue; }
            if (next instanceof Level level && other instanceof Level old) {
                duplicate("code", level.code().equalsIgnoreCase(old.code()));
                if (level.active() && old.active()) {
                    level.name().values().forEach((locale, name) -> duplicate("name", name.equalsIgnoreCase(old.name().values().get(locale))));
                }
            }
            if (next instanceof Ring ring && other instanceof Ring old) {
                duplicate("name", ring.name().equalsIgnoreCase(old.name())); duplicate("shortName", ring.shortName().equalsIgnoreCase(old.shortName()));
            }
        }
    }
    private void duplicate(String field, boolean duplicate) { if (duplicate) { throw new ApiException(ErrorCode.DUPLICATE_NAME, Map.of("field", field)); } }
    private Map<String, Object> fields(CatalogEntity entity) {
        Map<String, Object> fields = mapper.convertValue(entity, new TypeReference<>() { });
        for (String key : List.of("clubId", "version", "createdAt", "updatedAt", "createdByAccountId", "updatedByAccountId")) { fields.remove(key); }
        return fields;
    }
    private void publish(CatalogKind kind, CatalogEntity before, CatalogEntity after, String action) {
        var left = before == null ? Map.<String, Object>of() : fields(before);
        var right = after == null ? Map.<String, Object>of() : fields(after);
        var keys = new LinkedHashSet<>(left.keySet()); keys.addAll(right.keySet()); keys.remove("id");
        Map<String, Object> diff = new LinkedHashMap<>();
        for (String key : keys) {
            if (!Objects.equals(left.get(key), right.get(key))) {
                Map<String, Object> change = new LinkedHashMap<>(); change.put("before", left.get(key)); change.put("after", right.get(key)); diff.put(key, change);
            }
        }
        var entity = after == null ? before : after;
        events.publish(new CatalogEvent(kind, entity.clubId(), entity.id(), clock.instant(),
                Map.of("id", entity.id(), "action", action, "diff", diff), actors.current().accountId()));
    }
    private <T> T write(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof MongoException mongo && mongo.getCode() == 112) { throw new ApiException(ErrorCode.STALE_VERSION); }
            }
            if (failure instanceof DuplicateKeyException) { throw new ApiException(ErrorCode.STALE_VERSION); }
            throw failure;
        }
    }
}

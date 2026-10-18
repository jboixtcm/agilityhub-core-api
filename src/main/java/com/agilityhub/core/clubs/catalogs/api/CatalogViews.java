package com.agilityhub.core.clubs.catalogs.api;

import com.agilityhub.core.clubs.catalogs.application.CatalogService;
import com.agilityhub.core.clubs.catalogs.domain.*;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.application.audit.AuditQuery;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.shared.application.contract.ApiContracts.*;

@Component
public class CatalogViews {
    private final CatalogService catalogs;
    private final AuditQuery audit;
    private final ObjectMapper mapper;
    public CatalogViews(CatalogService catalogs, AuditQuery audit, ObjectMapper mapper) { this.catalogs = catalogs; this.audit = audit; this.mapper = mapper; }
    boolean admin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_ADMIN"));
    }
    void checkInactive(boolean includeInactive) { if (includeInactive && !admin()) { throw new ApiException(ErrorCode.FORBIDDEN); } }
    Map<String, Object> input(Object request) {
        Map<String, Object> fields = mapper.convertValue(request, new TypeReference<>() { });
        fields.values().removeIf(Objects::isNull);
        if (request instanceof CatalogRequests.RingPatch patch && patch.trainingCapacity() != null) {
            var value = patch.trainingCapacity();
            if (!value.isNull() && (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 20)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR);
            }
            fields.put("trainingCapacity", value.isNull() ? null : value.intValue());
        }
        return fields;
    }
    String create(CatalogKind kind, Object request) { return (String) catalogs.create(kind, input(request)).get("id"); }
    void update(CatalogKind kind, String id, Object request) { catalogs.update(kind, id, input(request)); }
    private String text(LocalizedText value) { return value.withDefaultLocale(catalogs.config().club().defaultLocale()).resolve(LocaleContext.current()).value(); }
    private LastChange lastChange(CatalogKind kind, String id) { return admin() ? project(audit.lastChange(kind.entityType(), id)) : null; }
    static LastChange project(AuditQuery.LastChange last) { return last == null ? null : new LastChange(last.at(), last.actorName(), last.action().name()); }
    /** E11-T10 (INC-38): the last changes of a whole list in one read (ADMIN only; the reader projection has none). */
    private Map<String, LastChange> lastChanges(CatalogKind kind, List<CatalogEntity> items) {
        var result = new HashMap<String, LastChange>();
        if (admin()) { audit.lastChanges(kind.entityType(), items.stream().map(CatalogEntity::id).toList()).forEach((id, last) -> result.put(id, project(last))); }
        return result;
    }
    /** E11-T10 (INC-38): the usage of a whole list in a fixed number of reads (ADMIN only). */
    private Map<String, Map<String, Long>> usages(CatalogKind kind, List<CatalogEntity> items) {
        return admin() ? catalogs.usage(kind, items.stream().map(CatalogEntity::id).toList()) : Map.of();
    }
    CatalogResponses.Level level(String id, boolean warnings) {
        var item = (Level) catalogs.get(CatalogKind.LEVEL, id);
        return level(item, warnings, admin() ? catalogs.usage(CatalogKind.LEVEL, item.id()) : null, lastChange(CatalogKind.LEVEL, item.id()));
    }
    Map<String, Object> levelDetail(String id) {
        var level = level(id, false);
        Map<String, Object> result = mapper.convertValue(level, new TypeReference<>() { });
        if (admin() && level.lastChange() == null) { result.put("lastChange", com.fasterxml.jackson.databind.node.NullNode.instance); }
        return result;
    }
    private CatalogResponses.Level level(Level item, boolean warnings, Map<String, Long> counts, LastChange lastChange) {
        var usage = counts == null ? null : mapper.convertValue(counts, CatalogResponses.LevelUsage.class);
        return new CatalogResponses.Level(item.id(), item.code(), text(item.name()), admin() ? item.name().values() : null,
                item.order(), item.color(), item.capacity(), item.grantsFreeTraining(), item.progression(), item.active(), usage,
                warnings && !item.active() ? usage : null, lastChange, item.version());
    }
    CatalogItems<CatalogResponses.Level> levels(boolean includeInactive) {
        checkInactive(includeInactive);
        var list = catalogs.list(CatalogKind.LEVEL, includeInactive);
        var usages = usages(CatalogKind.LEVEL, list); var changes = lastChanges(CatalogKind.LEVEL, list);
        var items = list.stream().map(item -> level((Level) item, false, usages.get(item.id()), changes.get(item.id()))).toList();
        return new CatalogItems<>(items, items.size());
    }
    CatalogResponses.Ring ring(String id) {
        var item = (Ring) catalogs.get(CatalogKind.RING, id);
        return ring(item, catalogs.config().get("training.capacityPerRingSlot", Integer.class), admin() ? catalogs.usage(CatalogKind.RING, item.id()) : null,
                lastChange(CatalogKind.RING, item.id()));
    }
    private CatalogResponses.Ring ring(Ring item, Integer capacityPerRingSlot, Map<String, Long> usage, LastChange lastChange) {
        return new CatalogResponses.Ring(item.id(), item.name(), item.shortName(), item.color(), item.allowsFreeTraining(), admin() ? item.trainingCapacity() : null,
                CapacityCalculator.forRing(item.trainingCapacity(), capacityPerRingSlot), item.order(), item.active(),
                usage == null ? null : mapper.convertValue(usage, CatalogResponses.RingUsage.class), lastChange, item.version());
    }
    CatalogItems<CatalogResponses.Ring> rings(boolean includeInactive) {
        checkInactive(includeInactive);
        var list = catalogs.list(CatalogKind.RING, includeInactive);
        var usages = usages(CatalogKind.RING, list); var changes = lastChanges(CatalogKind.RING, list);
        Integer capacityPerRingSlot = catalogs.config().get("training.capacityPerRingSlot", Integer.class);
        var items = list.stream().map(item -> ring((Ring) item, capacityPerRingSlot, usages.get(item.id()), changes.get(item.id()))).toList();
        return new CatalogItems<>(items, items.size());
    }
    CatalogResponses.FaqEntry faq(String id) { var item = (FaqEntry) catalogs.get(CatalogKind.FAQ, id); return faq(item, lastChange(CatalogKind.FAQ, item.id())); }
    private CatalogResponses.FaqEntry faq(FaqEntry item, LastChange lastChange) {
        return new CatalogResponses.FaqEntry(item.id(), text(item.category()), admin() ? item.category().values() : null,
                text(item.question()), admin() ? item.question().values() : null, text(item.answer()), admin() ? item.answer().values() : null,
                item.order(), item.active(), lastChange, item.version());
    }
    CatalogItems<CatalogResponses.FaqEntry> faqs(boolean includeInactive) {
        checkInactive(includeInactive);
        // The sorted input fixes category order by its minimum entry order; resolved labels define grouping.
        Map<String, List<CatalogResponses.FaqEntry>> groups = new LinkedHashMap<>();
        var list = catalogs.list(CatalogKind.FAQ, includeInactive); var changes = lastChanges(CatalogKind.FAQ, list);
        list.stream().map(item -> faq((FaqEntry) item, changes.get(item.id())))
                .forEach(item -> groups.computeIfAbsent(item.category(), ignored -> new ArrayList<>()).add(item));
        var items = groups.values().stream().flatMap(List::stream).toList();
        return new CatalogItems<>(items, items.size());
    }
    FilterValues categories(String field, String q, List<String> filters) {
        if (!"category".equals(field)) { throw new ApiException(ErrorCode.INVALID_FILTER); }
        if (filters != null) {
            for (String filter : filters) {
                String[] parts = filter.split(":", 3);
                if (parts.length != 3 || !parts[0].equals("category")
                        || !List.of("eq", "ne", "contains", "startsWith", "in", "nin").contains(parts[1])) {
                    throw new ApiException(ErrorCode.INVALID_FILTER);
                }
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (var item : faqs(true).items()) {
            String value = item.category();
            if ((q == null || value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) && matches(value, filters)) {
                counts.merge(value, 1L, Long::sum);
            }
        }
        return new FilterValues(field, counts.entrySet().stream().map(entry -> new FilterValue(entry.getKey(), entry.getKey(), entry.getValue())).toList());
    }
    private boolean matches(String value, List<String> filters) {
        if (filters == null) { return true; }
        for (String filter : filters) {
            String[] parts = filter.split(":", 3);
            boolean match = switch (parts[1]) {
                case "eq" -> value.equals(parts[2]);
                case "ne" -> !value.equals(parts[2]);
                case "contains" -> value.toLowerCase(Locale.ROOT).contains(parts[2].toLowerCase(Locale.ROOT));
                case "startsWith" -> value.startsWith(parts[2]);
                case "in" -> Arrays.asList(parts[2].split(",")).contains(value);
                case "nin" -> !Arrays.asList(parts[2].split(",")).contains(value);
                default -> throw new ApiException(ErrorCode.INVALID_FILTER);
            };
            if (!match) { return false; }
        }
        return true;
    }
}

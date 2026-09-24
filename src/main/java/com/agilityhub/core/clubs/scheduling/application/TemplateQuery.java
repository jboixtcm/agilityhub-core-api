package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.github.benmanes.caffeine.cache.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

@Service
public class TemplateQuery {
    private record Key(String club, String locale, String id) { }
    private final CacheLoads<Key, WeekTemplate> cache;
    private final WeekTemplateRepository templates;
    private final PlanningContext context;
    public TemplateQuery(WeekTemplateRepository templates, PlanningContext context, Clock clock) {
        this.templates = templates; this.context = context;
        cache = CacheLoads.of(Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(Duration.ofSeconds(60))
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis())).build());
    }
    public WeekTemplate require(String id) { return templates.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public PlanningViews.WeekTemplates list(TemplateKind kind, Boolean active) {
        var items = templates.findAll().stream().filter(t -> kind == null || t.kind() == kind).filter(t -> active == null || t.active() == active)
                .sorted(Comparator.comparing(WeekTemplate::name).thenComparing(WeekTemplate::id)).map(t -> {
                    var view = view(t);
                    return new PlanningViews.WeekTemplateSummary(t.id(), t.name(), t.kind(), t.active(), t.classes().size(), view.inconsistencies().size(), t.updatedAt());
                }).toList();
        return new PlanningViews.WeekTemplates(items);
    }
    public PlanningViews.WeekTemplate get(String id) {
        var template = TransactionSynchronizationManager.isActualTransactionActive() ? require(id)
                : cache.get(new Key(TenantContext.require(), LocaleContext.current().toLanguageTag(), id), key -> require(key.id()));
        // Catalog-derived capacity, labels and inconsistencies always reflect the live catalog.
        return view(template);
    }
    public PlanningViews.WeekTemplate view(WeekTemplate template) {
        var config = context.config(); var catalog = context.catalog();
        var inconsistencies = inconsistencies(template, catalog);
        var classes = template.classes().stream().map(c -> new PlanningViews.TemplateClass(c.id(), c.bandId(), c.dayOfWeek(), c.instructorIds(),
                c.ringId(), c.levelIds(), context.capacity(c.levelIds(), c.capacityMode(), c.capacity(), catalog, config), c.capacityMode(), c.description(),
                context.descriptions().resolve(c.description(), c.levelIds(), catalog, LocaleContext.current()),
                inconsistencies.stream().filter(i -> i.itemIds().contains(c.id())).map(PlanningViews.Inconsistency::id).toList(),
                config.modules().contains(Module.COURSES) ? c.placementId() : null)).toList();
        return new PlanningViews.WeekTemplate(template.id(), template.name(), template.kind(), WeekTemplateRules.days(template.kind()), template.notes(),
                template.active(), template.version(), template.timeBands().stream().map(b -> new PlanningViews.TimeBand(b.id(), b.startTime(), b.endTime())).toList(),
                classes, inconsistencies, inconsistencies.isEmpty());
    }
    public List<PlanningViews.Inconsistency> inconsistencies(WeekTemplate template, SchedulingCatalog catalog) {
        var bands = new HashMap<String, WeekTemplate.TimeBand>(); template.timeBands().forEach(b -> bands.put(b.id(), b));
        var items = template.classes().stream().map(c -> {
            var band = bands.get(c.bandId());
            return new InconsistencyDetector.ScheduledItem(c.id(), InconsistencyDetector.Kind.CLASS, c.dayOfWeek(), null,
                    LocalTime.parse(band.startTime()), LocalTime.parse(band.endTime()), c.bandId(), c.ringId(), c.instructorIds(), c.levelIds());
        }).toList();
        return context.detector().detect(items, catalog, false, LocaleContext.current()).stream().map(i ->
                new PlanningViews.Inconsistency(i.id(), i.type(), i.dayOfWeek(), i.date(), i.startTime(), i.bandId(), i.ringId(), i.instructorId(), i.levelId(), i.itemIds(), i.message())).toList();
    }
    public void invalidate(String clubId) { cache.invalidateIf(k -> k.club().equals(clubId)); }
    public void invalidateAfterCommit(String clubId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { invalidate(clubId); } });
        } else { invalidate(clubId); }
    }
}

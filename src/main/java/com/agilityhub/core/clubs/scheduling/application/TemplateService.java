package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class TemplateService {
    private final WeekTemplateRepository templates;
    private final TemplateQuery query;
    private final PlanningContext context;
    private final EventPublisher events;
    private final AuditActorProvider actors;
    private final ObjectMapper mapper;
    private final Clock clock;
    public TemplateService(WeekTemplateRepository templates, TemplateQuery query, PlanningContext context,
            EventPublisher events, AuditActorProvider actors, ObjectMapper mapper, Clock clock) {
        this.templates = templates; this.query = query; this.context = context; this.events = events; this.actors = actors; this.mapper = mapper; this.clock = clock;
    }
    @Transactional
    public PlanningViews.WeekTemplate create(String name, TemplateKind kind, String copyFromId) {
        return write(() -> {
            context.lockReferences(); var source = copyFromId == null ? null : query.require(copyFromId);
            if (source != null && source.kind() != kind) { throw new ApiException(ErrorCode.TEMPLATE_KIND_MISMATCH); }
            var bands = new ArrayList<WeekTemplate.TimeBand>(); var classes = new ArrayList<WeekTemplate.TemplateClass>();
            if (source != null) {
                var ids = new HashMap<String, String>();
                for (var band : source.timeBands()) { String id = id(); ids.put(band.id(), id); bands.add(new WeekTemplate.TimeBand(id, band.startTime(), band.endTime())); }
                for (var c : source.classes()) { classes.add(new WeekTemplate.TemplateClass(id(), ids.get(c.bandId()), c.dayOfWeek(), c.instructorIds(), c.ringId(), c.levelIds(), c.capacity(), c.capacityMode(), c.description(), c.placementId())); }
            }
            var now = clock.instant(); var actor = actors.current().accountId();
            var next = new WeekTemplate(id(), TenantContext.require(), name(name), kind, source == null ? null : source.notes(), true,
                    List.copyOf(bands), List.copyOf(classes), 0L, now, actor, now, actor);
            unique(next); templates.insert(next); return publish(null, next);
        });
    }
    @Transactional
    public PlanningViews.WeekTemplate patch(String id, long version, Map<String, Object> patch) {
        return write(() -> {
            context.lockReferences(); var before = current(id, version);
            var next = changed(before, patch.containsKey("name") ? name((String) patch.get("name")) : before.name(),
                    (String) patch.getOrDefault("notes", before.notes()), (Boolean) patch.getOrDefault("active", before.active()), before.timeBands(), before.classes());
            unique(next); return save(before, next);
        });
    }
    @Transactional
    public PlanningViews.WeekTemplate addBand(String id, String start, String end) {
        return write(() -> {
            var before = query.require(id); var bands = new ArrayList<>(before.timeBands());
            var band = new WeekTemplate.TimeBand(id(), start, end); validateBand(before, band); bands.add(band);
            return save(before, changed(before, bands, before.classes()));
        });
    }
    @Transactional
    public PlanningViews.WeekTemplate patchBand(String id, String bandId, long version, String start, String end) {
        return write(() -> {
            var before = current(id, version); var old = band(before, bandId);
            var next = new WeekTemplate.TimeBand(bandId, start == null ? old.startTime() : start, end == null ? old.endTime() : end);
            validateBand(before, next);
            return save(before, changed(before, before.timeBands().stream().map(b -> b.id().equals(bandId) ? next : b).toList(), before.classes()));
        });
    }
    @Transactional
    @Audited(action = AuditAction.TEMPLATE_BAND_DELETED, entityType = "'TimeBand'", entity = "#bandId", before = "bandSnapshot(#id, #bandId)")
    public void deleteBand(String id, String bandId) {
        write(() -> {
            var before = query.require(id); band(before, bandId);
            WeekTemplateRules.removable(before.classes().stream().anyMatch(c -> c.bandId().equals(bandId)));
            save(before, changed(before, before.timeBands().stream().filter(b -> !b.id().equals(bandId)).toList(), before.classes())); return null;
        });
    }
    @Transactional
    public PlanningViews.WeekTemplate addClass(String id, String bandId, DayOfWeek day, List<String> instructors, String ringId,
            List<String> levels, Integer capacity, String description) {
        return write(() -> {
            context.lockReferences(); var before = query.require(id);
            var next = buildClass(before, id(), bandId, day, instructors, ringId, levels, capacity, description, null);
            var classes = new ArrayList<>(before.classes()); classes.add(next);
            return save(before, changed(before, before.timeBands(), classes));
        });
    }
    @Transactional
    @SuppressWarnings("unchecked")
    public PlanningViews.WeekTemplate patchClass(String id, String classId, long version, Map<String, Object> patch) {
        return write(() -> {
            context.lockReferences(); var before = current(id, version); var old = templateClass(before, classId);
            Integer capacity = patch.containsKey("capacity") ? (Integer) patch.get("capacity") : old.capacityMode() == CapacityMode.MANUAL ? old.capacity() : null;
            var next = buildClass(before, classId, (String) patch.getOrDefault("bandId", old.bandId()), (DayOfWeek) patch.getOrDefault("dayOfWeek", old.dayOfWeek()),
                    (List<String>) patch.getOrDefault("instructorIds", old.instructorIds()), (String) patch.getOrDefault("ringId", old.ringId()),
                    (List<String>) patch.getOrDefault("levelIds", old.levelIds()), capacity, (String) patch.getOrDefault("description", old.description()), old.placementId());
            return save(before, changed(before, before.timeBands(), before.classes().stream().map(c -> c.id().equals(classId) ? next : c).toList()));
        });
    }
    @Transactional
    @Audited(action = AuditAction.TEMPLATE_CLASS_DELETED, entityType = "'TemplateClass'", entity = "#classId", before = "classSnapshot(#id, #classId)")
    public void deleteClass(String id, String classId) {
        write(() -> {
            context.lockReferences(); var before = query.require(id); templateClass(before, classId);
            save(before, changed(before, before.timeBands(), before.classes().stream().filter(c -> !c.id().equals(classId)).toList())); return null;
        });
    }
    public Map<String, Object> bandSnapshot(String id, String bandId) { return fields(band(query.require(id), bandId)); }
    public Map<String, Object> classSnapshot(String id, String classId) { return fields(templateClass(query.require(id), classId)); }
    private WeekTemplate.TimeBand band(WeekTemplate template, String id) {
        return template.timeBands().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
    private WeekTemplate.TemplateClass templateClass(WeekTemplate template, String id) {
        return template.classes().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
    private WeekTemplate current(String id, long version) {
        var current = query.require(id); if (current.version() != version) { throw new ApiException(ErrorCode.STALE_VERSION); } return current;
    }
    private void validateBand(WeekTemplate template, WeekTemplate.TimeBand band) {
        var config = context.config();
        WeekTemplateRules.band(new WeekTemplateRules.Band(band.id(), WeekTemplateRules.time(band.startTime()), WeekTemplateRules.time(band.endTime())),
                template.timeBands().stream().map(b -> new WeekTemplateRules.Band(b.id(), WeekTemplateRules.time(b.startTime()), WeekTemplateRules.time(b.endTime()))).toList(),
                template.kind(), config.get("classes.slotMinutes", Integer.class), context.opening(config));
    }
    private WeekTemplate.TemplateClass buildClass(WeekTemplate template, String id, String bandId, DayOfWeek day, List<String> instructors,
            String ringId, List<String> levels, Integer capacity, String description, String placementId) {
        var config = context.config(); var catalog = context.catalog();
        TemplateClassRules.validate(template.kind(), new HashSet<>(template.timeBands().stream().map(WeekTemplate.TimeBand::id).toList()), bandId, day,
                instructors, ringId, levels, description, config.get("classes.maxInstructorsPerClass", Integer.class), config.get("levels.enabled", Boolean.class), catalog);
        var mode = capacity == null ? CapacityMode.AUTO : CapacityMode.MANUAL;
        return new WeekTemplate.TemplateClass(id, bandId, day, List.copyOf(instructors), ringId, List.copyOf(levels),
                context.capacity(levels, mode, capacity == null ? 0 : capacity, catalog, config), mode,
                description == null || description.isBlank() ? null : description, placementId);
    }
    private WeekTemplate changed(WeekTemplate before, List<WeekTemplate.TimeBand> bands, List<WeekTemplate.TemplateClass> classes) {
        return changed(before, before.name(), before.notes(), before.active(), bands, classes);
    }
    private WeekTemplate changed(WeekTemplate before, String name, String notes, boolean active, List<WeekTemplate.TimeBand> bands, List<WeekTemplate.TemplateClass> classes) {
        return new WeekTemplate(before.id(), before.clubId(), name, before.kind(), notes, active, List.copyOf(bands), List.copyOf(classes), before.version() + 1,
                before.createdAt(), before.createdByAccountId(), clock.instant(), actors.current().accountId());
    }
    private PlanningViews.WeekTemplate save(WeekTemplate before, WeekTemplate next) { templates.update(next, before.version()); return publish(before, next); }
    private PlanningViews.WeekTemplate publish(WeekTemplate before, WeekTemplate next) {
        var view = query.view(next); var actor = actors.current();
        var left = before == null ? Map.<String, Object>of() : fields(before); var right = fields(next);
        var diff = new LinkedHashMap<String, Object>();
        for (String key : List.of("name", "notes", "active", "timeBands", "classes")) {
            if (!Objects.equals(left.get(key), right.get(key))) {
                var change = new LinkedHashMap<String, Object>(); change.put("before", left.get(key)); change.put("after", right.get(key)); diff.put(key, change);
            }
        }
        events.publish(new SchedulingEvent(SchedulingEvent.Kind.WeekTemplateChanged, next.clubId(), next.id(), clock.instant(),
                Map.of("templateId", next.id(), "diff", diff, "inconsistencies", view.inconsistencies()), actor.accountId(), actor.impersonatedMemberId(), DomainEvent.Origin.BACKOFFICE));
        query.invalidateAfterCommit(next.clubId()); return view;
    }
    private Map<String, Object> fields(Object value) { return mapper.convertValue(value, new TypeReference<>() { }); }
    private String name(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 40) { throw new ApiException(ErrorCode.VALIDATION_ERROR); } return value.strip();
    }
    private void unique(WeekTemplate next) {
        if (templates.findAll().stream().anyMatch(t -> !t.id().equals(next.id()) && t.kind() == next.kind() && t.name().equalsIgnoreCase(next.name()))) {
            throw new ApiException(ErrorCode.DUPLICATE_NAME);
        }
    }
    private String id() { return UUID.randomUUID().toString(); }
    private <T> T write(Supplier<T> action) {
        try { return action.get(); }
        catch (DuplicateKeyException duplicate) { throw new ApiException(ErrorCode.DUPLICATE_NAME); }
        catch (DataAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof com.mongodb.MongoException mongo && mongo.getCode() == 112) { throw new ApiException(ErrorCode.STALE_VERSION); }
            }
            throw failure;
        }
    }
}

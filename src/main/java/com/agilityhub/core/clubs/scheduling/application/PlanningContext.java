package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PlanningContext {
    private final ClubConfigService configs;
    private final PlanningCatalogAccess catalogs;
    private final IcuMessageSource messages;
    public PlanningContext(ClubConfigService configs, PlanningCatalogAccess catalogs, IcuMessageSource messages) {
        this.configs = configs; this.catalogs = catalogs; this.messages = messages;
    }
    public ClubConfig config() { return configs.get(TenantContext.require()); }
    public SchedulingCatalog catalog() {
        var source = catalogs.snapshot();
        return new SchedulingCatalog(source.levels().stream().map(l -> new SchedulingCatalog.Level(l.id(), l.name(), l.order(), l.capacity(), l.active())).toList(),
                source.rings().stream().map(r -> new SchedulingCatalog.Resource(r.id(), r.name(), r.active())).toList(),
                source.instructors().stream().map(i -> new SchedulingCatalog.Resource(i.id(), i.name(), i.active())).toList());
    }
    public void lockReferences() { catalogs.lockReferences(); }
    public DescriptionResolver descriptions() { return new DescriptionResolver(messages::format); }
    public InconsistencyDetector detector() { return new InconsistencyDetector(messages::format); }
    public int capacity(List<String> levels, CapacityMode mode, int stored, SchedulingCatalog catalog, ClubConfig config) {
        return mode == CapacityMode.MANUAL ? stored : PlanningCatalogAccess.capacity(catalog.levels().stream()
                .filter(l -> levels.contains(l.id())).map(SchedulingCatalog.Level::capacity).toList(),
                config.get("levels.enabled", Boolean.class), config.get("classes.defaultCapacity", Integer.class));
    }
    public Map<DayOfWeek, WeekTemplateRules.Opening> opening(ClubConfig config) {
        var result = new EnumMap<DayOfWeek, WeekTemplateRules.Opening>(DayOfWeek.class);
        config.get("club.openingHours", Map.class).forEach((day, raw) -> {
            if (raw instanceof Map<?, ?> hours && hours.get("open") != null && hours.get("close") != null) {
                result.put(DayOfWeek.valueOf(day.toString()), new WeekTemplateRules.Opening(LocalTime.parse(hours.get("open").toString()), LocalTime.parse(hours.get("close").toString())));
            }
        });
        return result;
    }
}

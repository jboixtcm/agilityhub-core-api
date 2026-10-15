package com.agilityhub.core.courses.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.courses.domain.AgilityHubLevel;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The club levels a course or a setup names, in catalog order (S05 `order`): their codes joined by an en dash («C–D», the D18 list
 * and R-16-09's `levelLabel`), else the AgilityHub level, and their names in a language (S09 R-09-15's `levelNames`).
 */
@Component
public class LevelLabels {
    private final PlanningCatalogAccess catalogs;
    public LevelLabels(PlanningCatalogAccess catalogs) { this.catalogs = catalogs; }

    /** The club's level codes by id, in catalog order; none outside a club. */
    public Map<String, String> codes() {
        var codes = new LinkedHashMap<String, String>();
        ordered().forEach(ref -> codes.put(ref.id(), ref.code()));
        return codes;
    }
    /** The codes of {@code levelIds} joined by an en dash, else {@code agilityhubLevel}'s name, else null. */
    public static String label(Map<String, String> codes, List<String> levelIds, AgilityHubLevel agilityhubLevel) {
        String level = codes.entrySet().stream().filter(entry -> levelIds.contains(entry.getKey())).map(Map.Entry::getValue).collect(Collectors.joining("–"));
        return !level.isEmpty() ? level : agilityhubLevel == null ? null : agilityhubLevel.name();
    }
    /** The names of {@code levelIds} in {@code locale}, in catalog order (each level's own default language as the fallback). */
    public List<String> names(List<String> levelIds, Locale locale) {
        return ordered().stream().filter(ref -> levelIds.contains(ref.id())).map(ref -> ref.name().resolve(locale).value()).toList();
    }
    private List<PlanningCatalogAccess.LevelRef> ordered() {
        if (TenantContext.current() == null) { return List.of(); }
        var order = new HashMap<String, Integer>();
        catalogs.snapshot().levels().forEach(level -> order.put(level.id(), level.order()));
        return catalogs.levelRefs().values().stream().sorted(Comparator.comparing(ref -> order.getOrDefault(ref.id(), Integer.MAX_VALUE))).toList();
    }
}

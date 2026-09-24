package com.agilityhub.core.clubs.dashboard.domain;

import com.agilityhub.core.clubs.dashboard.application.DashboardData.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DogActivityBuilder {
    private DogActivityBuilder() { }
    public static DogsByLevel build(List<LevelSource> levels, List<DogCount> counts, int weeks, String locale, String defaultLocale) {
        var byLevel = counts.stream().filter(c -> c.levelId() != null).collect(Collectors.toMap(DogCount::levelId, Function.identity()));
        // S14 R-14-07 (E35): the columns are the D3 coverage levels (active progression levels); every other dog counts in `others`.
        var rows = levels.stream().filter(level -> level.active() && level.progression()).sorted(Comparator.comparingInt(LevelSource::order).thenComparing(LevelSource::levelId))
                .map(level -> {
                    var count = byLevel.getOrDefault(level.levelId(), new DogCount(level.levelId(), 0, 0));
                    return new Level(level.levelId(), level.code(), level.name().withDefaultLocale(defaultLocale).resolve(locale).value(),
                            level.color(), count.total(), count.withRecentBooking());
                }).toList();
        int total = counts.stream().mapToInt(DogCount::total).sum();
        return new DogsByLevel(total, weeks, rows, total - rows.stream().mapToInt(Level::total).sum());
    }
}

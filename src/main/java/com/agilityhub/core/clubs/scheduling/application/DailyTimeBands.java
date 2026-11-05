package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.clubs.scheduling.domain.WeekCalendarRules;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** The bands selected when a week was generated, independent of later template edits. */
@Service
public class DailyTimeBands {
    public record Band(String startTime, String endTime) { }
    public record Day(LocalDate date, boolean configured, List<Band> bands) { }
    private final WeekRepository weeks;
    private final WeekTemplateRepository templates;
    public DailyTimeBands(WeekRepository weeks, WeekTemplateRepository templates) { this.weeks=weeks; this.templates=templates; }
    public Day get(LocalDate date) {
        var week=weeks.forStart(WeekCalendarRules.monday(date)).orElse(null);
        if (week==null || week.generatedAt()==null) return new Day(date,false,List.of());
        List<WeekTemplate.TimeBand> bands;
        if (week.dailyTimeBands()!=null) bands=week.dailyTimeBands().getOrDefault(date.getDayOfWeek().name(),List.of());
        else {
            // Compatibility for weeks generated before snapshots were introduced.
            String id=date.getDayOfWeek()==DayOfWeek.SATURDAY?week.saturdayTemplateId():date.getDayOfWeek()==DayOfWeek.SUNDAY?null:week.weekdayTemplateId();
            bands=id==null?List.of():templates.findById(id).map(WeekTemplate::timeBands).orElse(List.of());
        }
        return new Day(date,true,bands.stream().map(b -> new Band(b.startTime(),b.endTime())).distinct()
                .sorted(Comparator.comparing(Band::startTime)).toList());
    }
}

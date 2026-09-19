package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ports.TrainingConflictPort;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.LocaleContext;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CalendarQuery {
    private final WeekGenerationUseCase planning; private final ClassSessionRepository classes; private final RingBlockRepository blocks;
    private final PlanningContext context; private final TrainingConflictPort training; private final SessionProjection projection; private final Clock clock;
    public CalendarQuery(WeekGenerationUseCase planning,ClassSessionRepository classes,RingBlockRepository blocks,PlanningContext context,
            TrainingConflictPort training,SessionProjection projection,Clock clock) {
        this.planning=planning; this.classes=classes; this.blocks=blocks; this.context=context; this.training=training; this.projection=projection; this.clock=clock;
    }
    public List<InconsistencyDetector.Inconsistency> inconsistencies(Week week) {
        var items=new ArrayList<InconsistencyDetector.ScheduledItem>(); var zone=projection.zone();
        classes.forWeek(week.id()).stream().filter(c -> c.state()==ClassState.DRAFT || c.state()==ClassState.ACTIVE).forEach(c ->
                items.add(new InconsistencyDetector.ScheduledItem(c.id(),InconsistencyDetector.Kind.CLASS,c.date().getDayOfWeek(),c.date(),LocalTime.parse(c.startTime()),LocalTime.parse(c.endTime()),null,c.ringId(),c.instructorIds(),c.levelIds())));
        var from=week.startDate().atStartOfDay(zone).toInstant(); var to=week.endDate().plusDays(1).atStartOfDay(zone).toInstant();
        blocks.between(from,to).forEach(b -> intervals(items,b.id(),InconsistencyDetector.Kind.BLOCK,b.ringId(),b.from(),b.to(),week));
        boolean free=projection.enabled(Module.FREE_TRAINING);
        if(free) for(var ring:context.catalog().rings()) training.findActiveBookings(ring.id(),from,to).forEach(b -> intervals(items,b.id(),InconsistencyDetector.Kind.TRAINING,b.ringId(),b.from(),b.to(),week));
        return context.detector().detect(items,context.catalog(),free,LocaleContext.current());
    }
    private void intervals(List<InconsistencyDetector.ScheduledItem> items,String id,InconsistencyDetector.Kind kind,String ring,Instant from,Instant to,Week week) {
        var zone=projection.zone(); var start=from.atZone(zone); var end=to.atZone(zone);
        for(var date=start.toLocalDate();!date.isAfter(end.toLocalDate());date=date.plusDays(1)) {
            if(date.isBefore(week.startDate()) || date.isAfter(week.endDate())) continue;
            var s=date.equals(start.toLocalDate())?start.toLocalTime():LocalTime.MIN; var e=date.equals(end.toLocalDate())?end.toLocalTime():LocalTime.MAX;
            if(s.isBefore(e)) items.add(new InconsistencyDetector.ScheduledItem(id,kind,date.getDayOfWeek(),date,s,e,null,ring,List.of(),List.of()));
        }
    }
    public Map<String,Object> get(String id,String filter) {
        var week=planning.require(id); var all=classes.forWeek(id); var inconsistencies=inconsistencies(week); var zone=projection.zone();
        var selected=all.stream().filter(c -> switch(filter) { case "DRAFT" -> c.state()==ClassState.DRAFT; case "CANCELLED" -> c.state()==ClassState.CANCELLED; default -> c.state()!=ClassState.DRAFT; })
                .sorted(Comparator.comparing(ClassSession::startsAt).thenComparing(ClassSession::id)).toList();
        var ringBlocks=blocks.between(week.startDate().atStartOfDay(zone).toInstant(),week.endDate().plusDays(1).atStartOfDay(zone).toInstant()).stream().filter(projection::visible).toList();
        var rows=new TreeSet<String>(); selected.forEach(c -> rows.add(c.startTime())); ringBlocks.forEach(b -> rows.add(b.from().atZone(zone).toLocalTime().toString()));
        var current=WeekCalendarRules.monday(clock.instant().atZone(zone).toLocalDate()); var header=new LinkedHashMap<String,Object>();
        header.put("id",id); header.put("isoYear",week.isoYear()); header.put("isoWeek",week.isoWeek()); header.put("startDate",week.startDate()); header.put("endDate",week.endDate());
        header.put("state",week.state()); header.put("generatedAt",week.generatedAt()); header.put("validatedAt",week.validatedAt());
        header.put("relative",week.startDate().equals(current)?"CURRENT":week.startDate().equals(current.plusWeeks(1))?"NEXT":"OTHER");
        int draftCount=(int)all.stream().filter(c -> c.state()==ClassState.DRAFT).count();
        return Map.of("week",header,"rows",rows,"classes",selected.stream().map(c -> projection.session(c,false,inconsistencies.stream().filter(i -> i.itemIds().contains(c.id())).map(InconsistencyDetector.Inconsistency::id).toList())).toList(),
                "ringBlocks",ringBlocks.stream().map(b -> projection.block(b,false)).toList(),"inconsistencies",inconsistencies,"draftCount",draftCount,"canValidate",draftCount>0 && inconsistencies.isEmpty());
    }
}

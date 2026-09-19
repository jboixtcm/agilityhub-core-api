package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class WeekValidationUseCase {
    private final WeekRepository weeks; private final ClassSessionRepository classes; private final WeekGenerationUseCase planning;
    private final SchedulingTransactions transactions; private final SchedulingEvents events; private final SchedulingAudit audit;
    private final CalendarQuery calendar; private final Clock clock;
    public WeekValidationUseCase(WeekRepository weeks, ClassSessionRepository classes, WeekGenerationUseCase planning,
            SchedulingTransactions transactions, SchedulingEvents events, SchedulingAudit audit, CalendarQuery calendar, Clock clock) {
        this.weeks=weeks; this.classes=classes; this.planning=planning; this.transactions=transactions; this.events=events; this.audit=audit; this.calendar=calendar; this.clock=clock;
    }
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String,Object> validate(String id) {
        return transactions.write(() -> {
            var before=planning.require(id); var drafts=classes.forWeek(id).stream().filter(c -> c.state()==ClassState.DRAFT).toList();
            if(drafts.isEmpty()) throw new ApiException(ErrorCode.NOTHING_TO_VALIDATE);
            ClassSessionRules.transition(before.state(),WeekState.VALIDATED);
            var inconsistencies=calendar.inconsistencies(before);
            if(!inconsistencies.isEmpty()) throw new ApiException(ErrorCode.WEEK_INCONSISTENT,Map.of("inconsistencies",inconsistencies));
            var now=clock.instant(); var actor=events.actor();
            for(var draft:drafts) { var edit=new SessionEdit(draft); edit.state=ClassState.ACTIVE; classes.update(edit.snapshot(now,actor),draft.version()); }
            var after=new Week(before.id(),before.clubId(),before.isoYear(),before.isoWeek(),before.startDate(),before.endDate(),WeekState.VALIDATED,
                    before.generatedAt(),before.generatedByAccountId(),before.weekdayTemplateId(),before.saturdayTemplateId(),now,actor,before.version()+1,
                    before.createdAt(),before.createdByAccountId(),now,actor);
            weeks.update(after,before.version()); audit.validated(before,after);
            var ids=drafts.stream().map(ClassSession::id).toList();
            events.publish(SchedulingEvent.Kind.WeekValidated,id,Map.of("weekId",id,"classIds",ids));
            return Map.of("validatedClassIds",ids);
        });
    }
}

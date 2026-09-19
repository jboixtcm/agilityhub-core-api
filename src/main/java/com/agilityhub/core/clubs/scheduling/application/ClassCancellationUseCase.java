package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort;
import com.agilityhub.core.clubs.census.application.SchedulingRecipients;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ClassCancellationUseCase {
    private final ClassSessionService sessions; private final ClassSessionRepository classes; private final ClassBookingsPort bookings;
    private final SchedulingRecipients recipients; private final PlanningContext context; private final SchedulingTransactions transactions;
    private final SchedulingEvents events; private final SchedulingAudit audit; private final Clock clock;
    public ClassCancellationUseCase(ClassSessionService sessions,ClassSessionRepository classes,ClassBookingsPort bookings,SchedulingRecipients recipients,
            PlanningContext context,SchedulingTransactions transactions,SchedulingEvents events,SchedulingAudit audit,Clock clock) {
        this.sessions=sessions; this.classes=classes; this.bookings=bookings; this.recipients=recipients; this.context=context;
        this.transactions=transactions; this.events=events; this.audit=audit; this.clock=clock;
    }
    public ClassSession cancel(String id,ClassCancellationReason reason,String adminText,String actor) {
        return transactions.write(() -> {
            var before=sessions.require(id);
            ClassSessionRules.transition(before.state(),ClassState.CANCELLED,reason);
            var live=bookings.activeBookings(id); var waiting=bookings.liveWaitlist(id);
            if((before.counters().booked()>0 || before.counters().waiting()>0 || !live.isEmpty() || !waiting.isEmpty())
                    && (adminText==null || adminText.isBlank() || adminText.length()>500)) throw new ApiException(ErrorCode.ADMIN_TEXT_REQUIRED);
            var effects=bookings.cancelAllByClub(id,reason.name(),actor); var now=clock.instant();
            var edit=new SessionEdit(before); edit.state=ClassState.CANCELLED; edit.counters=new ClassSession.Counters(0,0);
            edit.cancellation=new ClassSession.Cancellation(reason,adminText,actor,now,effects.bookings().size(),effects.waitlist().size());
            var after=classes.update(edit.snapshot(now,actor),before.version());
            var payload=new LinkedHashMap<String,Object>(); payload.put("classId",id); payload.put("reason",reason); payload.put("adminText",adminText);
            payload.put("affected",effects.bookings().stream().map(b -> Map.of("bookingId",b.bookingId(),"memberId",b.memberId(),"dogId",b.dogId())).toList());
            payload.put("waitlistIds",effects.waitlist().stream().map(ClassBookingsPort.WaitlistRef::entryId).toList());
            events.publish(SchedulingEvent.Kind.ClassCancelledByClub,id,payload); audit.cancelled(before,after); return after;
        }, ErrorCode.INVALID_STATE);
    }
    public Map<String,Object> preview(String id) {
        sessions.require(id); var config=context.config(); var catalog=context.catalog();
        var rows=bookings.activeBookings(id).stream().map(b -> {
            var member=recipients.member(b.memberId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            var dog=recipients.dog(b.dogId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            var channels=new ArrayList<String>(); if(member.accountId()!=null) channels.add("APP"); if(member.email()!=null) channels.add("EMAIL");
            if(config.modules().contains(Module.SMS) && !member.phones().isEmpty()) channels.add("SMS");
            var row=new LinkedHashMap<String,Object>(); row.put("bookingId",b.bookingId()); row.put("memberName",member.name()); row.put("dogName",dog.name());
            row.put("levelName",catalog.levels().stream().filter(l -> l.id().equals(dog.levelId())).map(l -> l.name().resolve(LocaleContext.current()).value()).findFirst().orElse(null));
            row.put("channels",channels); row.put("phoneCount",member.phones().size()); return row;
        }).toList();
        return Map.of("bookings",rows,"waitlistCount",bookings.liveWaitlist(id).size());
    }
}

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
    private final SchedulingEvents events; private final SchedulingAudit audit; private final Clock clock; private final IcuMessageSource messages;
    public ClassCancellationUseCase(ClassSessionService sessions,ClassSessionRepository classes,ClassBookingsPort bookings,SchedulingRecipients recipients,
            PlanningContext context,SchedulingTransactions transactions,SchedulingEvents events,SchedulingAudit audit,Clock clock,IcuMessageSource messages) {
        this.sessions=sessions; this.classes=classes; this.bookings=bookings; this.recipients=recipients; this.context=context;
        this.transactions=transactions; this.events=events; this.audit=audit; this.clock=clock; this.messages=messages;
    }
    /** The admin's own cancellation (reason CLUB_MANUAL) for callers outside S06, which hold no S06 domain type (the E6-T04 demo seed). */
    public void cancelByClub(String id,String adminText,String actor) { cancel(id,ClassCancellationReason.CLUB_MANUAL,adminText,actor); }
    public ClassSession cancel(String id,ClassCancellationReason reason,String adminText,String actor) {
        return transactions.write(() -> {
            var before=sessions.require(id);
            ClassSessionRules.transition(before.state(),ClassState.CANCELLED,reason);
            var live=bookings.activeBookings(id); var waiting=bookings.liveWaitlist(id);
            if((before.counters().booked()>0 || before.counters().waiting()>0 || !live.isEmpty() || !waiting.isEmpty())
                    && (adminText==null || adminText.isBlank() || adminText.length()>500)) throw new ApiException(ErrorCode.ADMIN_TEXT_REQUIRED);
            var effects=bookings.cancelAllByClub(id,reason.name(),adminText,actor); var now=clock.instant();
            var edit=new SessionEdit(before); edit.state=ClassState.CANCELLED; edit.counters=new ClassSession.Counters(0,0);
            edit.cancellation=new ClassSession.Cancellation(reason,adminText,actor,now,effects.bookings().size(),effects.waitlist().size());
            var after=classes.update(edit.snapshot(now,actor),before.version());
            var payload=new LinkedHashMap<String,Object>(); payload.put("classId",id); payload.put("reason",reason); payload.put("adminText",adminText);
            payload.put("affected",effects.bookings().stream().map(b -> Map.of("bookingId",b.bookingId(),"memberId",b.memberId(),"dogId",b.dogId())).toList());
            payload.put("waitlistIds",effects.waitlist().stream().map(ClassBookingsPort.WaitlistRef::entryId).toList());
            events.publish(SchedulingEvent.Kind.ClassCancelledByClub,id,payload); audit.cancelled(before,after); return after;
        }, ErrorCode.INVALID_STATE);
    }
    /**
     * R-06-10 (D4c modal, T-06-15). INC-40 (E11-T10): a booking whose member the census no longer names (erased, R-14-15) or
     * whose dog is gone is still a row: the neutral `lists.erasedMember`, an empty dog name, no channels and no phones (nothing
     * will reach them), never a 404 for the whole preview.
     */
    public Map<String,Object> preview(String id) {
        sessions.require(id); var config=context.config(); var catalog=context.catalog(); var locale=LocaleContext.current();
        var rows=bookings.activeBookings(id).stream().map(b -> {
            var member=recipients.member(b.memberId()); var dog=recipients.dog(b.dogId());
            var channels=new ArrayList<String>();
            member.ifPresent(m -> { if(m.accountId()!=null) channels.add("APP"); if(m.email()!=null) channels.add("EMAIL"); if(config.modules().contains(Module.SMS) && !m.phones().isEmpty()) channels.add("SMS"); });
            var row=new LinkedHashMap<String,Object>(); row.put("bookingId",b.bookingId());
            row.put("memberName",member.map(SchedulingRecipients.Member::name).orElseGet(() -> messages.format("lists.erasedMember",Map.of(),locale)));
            row.put("dogName",dog.map(SchedulingRecipients.Dog::name).orElse(""));
            row.put("levelName",dog.flatMap(d -> catalog.levels().stream().filter(l -> l.id().equals(d.levelId())).map(l -> l.name().resolve(locale).value()).findFirst()).orElse(null));
            row.put("channels",channels); row.put("phoneCount",member.map(m -> m.phones().size()).orElse(0)); return row;
        }).toList();
        return Map.of("bookings",rows,"waitlistCount",bookings.liveWaitlist(id).size());
    }
}

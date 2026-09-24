package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.training.domain.*;
import com.agilityhub.core.clubs.training.persistence.TrainingBooking;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;

/**
 * S09 §7 on the outbox, inside the booking transaction: `TrainingBooked {trainingBookingId, slotId, ringId, memberId,
 * dogId, origin}` and `TrainingCancelled {…, by, cancelReason, late}` (CATALEG_ESDEVENIMENTS Annex A). `origin` is
 * the origin of the change itself (a club cancellation of an APP booking is BACKOFFICE; S13/S15 paths are SYSTEM).
 */
@Service
public class TrainingEvents {
    private final EventPublisher publisher; private final Clock clock;
    public TrainingEvents(EventPublisher publisher, Clock clock) { this.publisher = publisher; this.clock = clock; }
    public String booked(TrainingBooking b, TrainingActor actor) {
        return publish(TrainingEvent.Kind.TrainingBooked, base(b, actor), b, actor);
    }
    public String cancelled(TrainingBooking b, TrainingActor actor, boolean late) {
        var payload = base(b, actor); payload.put("by", b.cancelledBy()); payload.put("cancelReason", b.cancelReason()); payload.put("late", late);
        return publish(TrainingEvent.Kind.TrainingCancelled, payload, b, actor);
    }
    private static LinkedHashMap<String, Object> base(TrainingBooking b, TrainingActor actor) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("trainingBookingId", b.id()); payload.put("slotId", b.slotId()); payload.put("ringId", b.ringId());
        payload.put("memberId", b.memberId()); payload.put("dogId", b.dogId()); payload.put("origin", origin(actor).name());
        return payload;
    }
    private String publish(TrainingEvent.Kind kind, LinkedHashMap<String, Object> payload, TrainingBooking b, TrainingActor actor) {
        return publisher.publish(new TrainingEvent(kind, TenantContext.require(), b.id(), clock.instant(), payload,
                actor.by() == TrainingCancelledBy.SYSTEM ? null : actor.accountId(), actor.impersonatedMemberId(), origin(actor)));
    }
    static DomainEvent.Origin origin(TrainingActor actor) {
        if (actor.by() == TrainingCancelledBy.SYSTEM) { return DomainEvent.Origin.SYSTEM; }
        return actor.origin() == TrainingOrigin.BACKOFFICE ? DomainEvent.Origin.BACKOFFICE : DomainEvent.Origin.APP;
    }
}

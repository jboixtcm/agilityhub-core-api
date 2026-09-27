package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationValues;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort;
import com.agilityhub.core.clubs.scheduling.domain.SchedulingCatalog;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSession;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The S06/S15 class notices explained to the S11 engine (E7-T02; replaces `SchedulingNotifications` and `RiskNotifications`):
 * <ul>
 * <li>N-08a `ClassCancelledByClub`: the affected bookings and the live waiting-list entries, one per dog (S11 §7); the class's
 * instructors and the admins too unless `reason = RISK_REVIEW`, whose members read the automatic text in their own
 * language. A class without registrants notifies nobody.</li>
 * <li>N-08b `ClassSessionUpdated`: only with bookings and a change of start time, ring or instructors; the active bookings,
 * and the new and former instructors; `changes` «Hora: 18:50 → 19:00 · Pista: Central → Muntanya».</li>
 * <li>N-16 `ClassAtRisk`: the new bookings; the admins only when the event says so (the first day).</li>
 * <li>N-17 `ClassAutoCancelled` and N-54 `ClassBelowMinimum`: the class's instructors and the admins, also with no registrants.</li>
 * </ul>
 */
@Service
public class SchedulingNotificationFacts implements NotificationFactsPort {
    private static final Set<String> TYPES = Set.of("ClassCancelledByClub", "ClassSessionUpdated", "ClassAtRisk", "ClassAutoCancelled", "ClassBelowMinimum");
    private static final List<String> CHANGED = List.of("startTime", "ringId", "instructorIds");
    private final ClassSessionService sessions; private final ClassBookingsPort bookings; private final PlanningContext context;
    private final SessionProjection projection; private final IcuMessageSource messages;

    public SchedulingNotificationFacts(ClassSessionService sessions, ClassBookingsPort bookings, PlanningContext context, SessionProjection projection,
            IcuMessageSource messages) {
        this.sessions = sessions; this.bookings = bookings; this.context = context; this.projection = projection; this.messages = messages;
    }

    @Override public Set<String> eventTypes() { return TYPES; }

    @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
        ClassSession c;
        try { c = sessions.require(Objects.requireNonNullElse(trigger.text("classId"), trigger.aggregateId())); }
        catch (ApiException gone) { return Optional.empty(); }
        var builder = NotificationFacts.builder().subject(NotificationSubject.classSession(c.id())).instructors(c.id(), List.of())
                .value("class_date", c.startsAt()).value("class_time", c.startsAt())
                .value("class_description", new NotificationValues.Localized(locale -> projection.description(c, locale)))
                .value("ring_name", ring(c.ringId())).value("entityId", c.id());
        return switch (trigger.type()) {
            case "ClassCancelledByClub" -> cancelled(trigger, c, builder);
            case "ClassSessionUpdated" -> updated(trigger, c, builder);
            case "ClassAtRisk" -> atRisk(trigger, c, builder);
            case "ClassAutoCancelled" -> Optional.of(builder.value("dogs_count", trigger.number("dogsCount", 0)).build());
            case "ClassBelowMinimum" -> Optional.of(builder.value("dogs_count", trigger.number("countedDogs", trigger.number("dogsCount", 0))).build());
            default -> Optional.empty();
        };
    }

    private Optional<NotificationFacts> cancelled(NotificationTrigger trigger, ClassSession c, NotificationFacts.Builder builder) {
        var students = new ArrayList<NotificationFacts.MemberSubject>();
        for (var row : trigger.rows("affected")) {
            students.add(new NotificationFacts.MemberSubject(row.get("memberId").toString(), Objects.toString(row.get("dogId"), null), Map.of(),
                    NotificationSubject.booking(Objects.toString(row.get("bookingId"), null))));
        }
        for (var entry : bookings.waitlistEntries(trigger.ids("waitlistIds"))) {
            students.add(new NotificationFacts.MemberSubject(entry.memberId(), entry.dogId(), Map.of(), NotificationSubject.waitlistEntry(entry.entryId())));
        }
        if (students.isEmpty()) { return Optional.empty(); }
        students.forEach(builder::member);
        if ("RISK_REVIEW".equals(trigger.text("reason"))) {
            // S15 R-15-12 / S11 §7: the members only (the staff get N-17), with the automatic text in each member's language.
            int minDogs = Objects.requireNonNullElse(context.config().get("classes.minDogs", Integer.class), 0);
            builder.audiences("MEMBER")
                    .value("admin_text", new NotificationValues.Localized(locale -> messages.format("scheduling.autoCancel.text", Map.of("minDogs", minDogs), locale)));
        } else { builder.value("admin_text", Objects.toString(trigger.text("adminText"), "")); }
        return Optional.of(builder.build());
    }

    @SuppressWarnings("unchecked")
    private Optional<NotificationFacts> updated(NotificationTrigger trigger, ClassSession c, NotificationFacts.Builder builder) {
        var diff = trigger.map("diff");
        if (trigger.number("bookedCount", 0) == 0 || Collections.disjoint(diff.keySet(), CHANGED)) { return Optional.empty(); }
        var live = bookings.activeBookings(c.id());
        if (live.isEmpty()) { return Optional.empty(); }
        live.forEach(b -> builder.member(new NotificationFacts.MemberSubject(b.memberId(), b.dogId(), Map.of(), NotificationSubject.booking(b.bookingId()))));
        var former = new ArrayList<String>();
        if (diff.get("instructorIds") instanceof Map<?, ?> change && change.get("before") instanceof List<?> previous) { previous.forEach(id -> former.add(id.toString())); }
        builder.instructors(c.id(), former);
        var changes = new ArrayList<NotificationValues.Change>();
        for (String field : CHANGED) {
            if (diff.get(field) instanceof Map<?, ?> change) {
                changes.add(new NotificationValues.Change("scheduling.change." + field,
                        Map.of("before", display(field, change.get("before")), "after", display(field, change.get("after")))));
            }
        }
        return Optional.of(builder.value("changes", new NotificationValues.Changes(changes)).build());
    }

    @SuppressWarnings("unchecked")
    private Optional<NotificationFacts> atRisk(NotificationTrigger trigger, ClassSession c, NotificationFacts.Builder builder) {
        var config = context.config();
        for (var b : bookings.bookings(trigger.ids("newBookingIds"))) {
            builder.member(new NotificationFacts.MemberSubject(b.memberId(), b.dogId(), Map.of(), NotificationSubject.booking(b.bookingId())));
        }
        if (trigger.ids("newBookingIds").isEmpty()) { builder.noMembers(); }
        if (!trigger.flag("notifyAdmins")) { builder.audiences("MEMBER"); }
        Object reviewAt = trigger.payload().get("reviewAt");
        return Optional.of(builder.value("review_time", config.get("classes.riskReviewTime", String.class))
                .value("review_day", reviewAt == null ? c.date() : java.time.Instant.parse(reviewAt.toString()))
                .value("auto_cancel", Boolean.TRUE.equals(config.get("classes.riskAutoCancelSameDay", Boolean.class)) ? "true" : "false")
                .value("dogs_count", trigger.number("dogsCount", 0)).build());
    }

    private String ring(String ringId) {
        return context.catalog().rings().stream().filter(r -> r.id().equals(ringId)).map(SchedulingCatalog.Resource::name).findFirst().orElse("");
    }
    private String display(String field, Object value) {
        if (field.equals("startTime")) { return Objects.toString(value, ""); }
        var catalog = context.catalog();
        if (field.equals("ringId")) { return catalog.rings().stream().filter(r -> r.id().equals(value)).map(SchedulingCatalog.Resource::name).findFirst().orElse("—"); }
        if (value instanceof List<?> ids) { return String.join(", ", catalog.instructors().stream().filter(i -> ids.contains(i.id())).map(SchedulingCatalog.Resource::name).toList()); }
        return "";
    }
}

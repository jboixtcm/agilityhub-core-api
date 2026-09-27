package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.persistence.Activity;
import com.agilityhub.core.clubs.activities.persistence.ActivityRegistrationRepository;
import com.agilityhub.core.clubs.activities.persistence.ActivityRepository;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationValues;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * S07 notices explained to the S11 engine (E7-T02; replaces `ActivityNotifications` and `ActivitySms`, rules unchanged):
 * N-32a `ActivityPublished` to the members whose level the activity admits (e-mail only when the event says `notifyEmail`);
 * N-32b `ActivityRegistrationChanged` to the registrant (nothing for `MEMBER_LEFT` or `ACTIVITY_CANCELLED`; made from the
 * back office it is the catalog's `CLUB_CHANGES` variant, S07); N-32c `ActivityCancelled` to every affected registrant;
 * N-32d `ActivityUpdated` with registrants and a change of date, times, place or rings, to the registrations alive when
 * it happened. One notice per member (the subject is the activity).
 */
@Service
public class ActivityNotificationFacts implements NotificationFactsPort {
    private static final Set<String> TYPES = Set.of("ActivityPublished", "ActivityRegistrationChanged", "ActivityCancelled", "ActivityUpdated");
    private static final List<String> CHANGED = List.of("date", "startTime", "endTime", "location", "ringIds");
    private final ActivityRepository activities; private final ActivityRegistrationRepository registrations; private final ActivityAudienceService audience;
    private final ActivityProjection projection;

    public ActivityNotificationFacts(ActivityRepository activities, ActivityRegistrationRepository registrations, ActivityAudienceService audience,
            ActivityProjection projection) {
        this.activities = activities; this.registrations = registrations; this.audience = audience; this.projection = projection;
    }

    @Override public Set<String> eventTypes() { return TYPES; }

    @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
        Activity a;
        try { a = activities.require(Objects.requireNonNullElse(trigger.text("activityId"), trigger.aggregateId())); }
        catch (ApiException gone) { return Optional.empty(); }
        var members = new LinkedHashSet<String>();
        var builder = NotificationFacts.builder().subject(NotificationSubject.activity(a.id()))
                .value("activity_title", new NotificationValues.Localized(locale -> { try (var scope = LocaleContext.open(locale)) { return projection.title(a); } }))
                .value("date", a.date()).value("admin_text", Objects.toString(trigger.text("adminText"), "")).value("state", Objects.toString(trigger.text("state"), ""))
                .value("entityId", a.id());
        switch (trigger.type()) {
            case "ActivityPublished" -> {
                members.addAll(audience.admittedMemberIds(a));
                if (trigger.flag("notifyEmail")) { builder.enable("EMAIL"); }
            }
            case "ActivityCancelled" -> trigger.rows("affected").forEach(row -> members.add(row.get("memberId").toString()));
            case "ActivityRegistrationChanged" -> {
                if (Set.of("MEMBER_LEFT", "ACTIVITY_CANCELLED").contains(Objects.toString(trigger.text("cancelReason"), ""))) { return Optional.empty(); }
                members.add(trigger.text("memberId"));
                if ("BACKOFFICE".equals(trigger.text("origin"))) { builder.category("CLUB_CHANGES"); }
            }
            case "ActivityUpdated" -> {
                var diff = trigger.map("diff");
                if (trigger.number("registrantCount", 0) == 0 || Collections.disjoint(diff.keySet(), CHANGED)) { return Optional.empty(); }
                registrations.forActivity(a.id()).stream()
                        .filter(r -> !r.registeredAt().isAfter(trigger.occurredAt()) && (r.cancelledAt() == null || !r.cancelledAt().isBefore(trigger.occurredAt())))
                        .forEach(r -> members.add(r.memberId()));
                builder.value("changes", changes(a, diff));
            }
            default -> { return Optional.empty(); }
        }
        members.remove(null);
        if (members.isEmpty()) { return Optional.empty(); }
        members.forEach(memberId -> builder.member(memberId, null));
        return Optional.of(builder.build());
    }

    private NotificationValues.Changes changes(Activity activity, Map<String, Object> diff) {
        var items = new ArrayList<NotificationValues.Change>();
        for (String field : CHANGED) {
            if (!diff.containsKey(field)) { continue; }
            Object value = switch (field) {
                case "date" -> activity.date(); case "startTime" -> activity.startTime(); case "endTime" -> activity.endTime();
                default -> new NotificationValues.Localized(locale -> { try (var scope = LocaleContext.open(locale)) { return projection.place(activity); } });
            };
            items.add(new NotificationValues.Change("activities.change." + field, Map.of("value", value == null ? "" : value)));
        }
        return new NotificationValues.Changes(items);
    }
}

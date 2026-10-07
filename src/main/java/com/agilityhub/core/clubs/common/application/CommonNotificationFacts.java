package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.census.application.SchedulingRecipients;
import com.agilityhub.core.clubs.common.domain.ForeignEvent;
import com.agilityhub.core.clubs.messaging.application.engine.NotificationEngine;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationValues;
import com.agilityhub.core.clubs.scheduling.application.WeekOpenings;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

/**
 * The S15 process notices explained to the S11 engine (E7-T02; replaces `JobFailureNotifications` and
 * `WeekOpeningNotifications`, rules unchanged):
 * <ul>
 * <li>N-42 `JobFailed` → the ADMINS when `jobs.alertAdminsOnFailure`, at most once per process and club-local day
 * (occurrence `N-42:{job}:{day}`, R-15-10).</li>
 * <li>N-33 `WeekOpened{notified: true}` → every ACTIVE member with an ACTIVE dog and an app account (S15 R-15-11), once per
 * week (occurrence `N-33:{weekId}`); the deferred case — `WeekValidated` of a week whose booking has opened and was not
 * notified yet — is the same notice, emitted through the engine by the durable consumer `notifications.N-33.deferred`,
 * which then marks the week.</li>
 * </ul>
 */
@Service
public class CommonNotificationFacts implements NotificationFactsPort {
    private static final Set<String> TYPES = Set.of("JobFailed", "WeekOpened", "SignupPendingAging", "RemittanceReminderDue");
    private final ClubConfigService configs; private final IcuMessageSource messages; private final WeekOpenings weeks; private final SchedulingRecipients recipients;

    public CommonNotificationFacts(ClubConfigService configs, IcuMessageSource messages, WeekOpenings weeks, SchedulingRecipients recipients) {
        this.configs = configs; this.messages = messages; this.weeks = weeks; this.recipients = recipients;
    }

    @Override public Set<String> eventTypes() { return TYPES; }

    @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
        var config = configs.get(trigger.clubId());
        return switch (trigger.type()) {
            case "SignupPendingAging" -> Optional.of(NotificationFacts.builder()
                    .occurrence("N-34:" + trigger.occurredAt().atZone(configs.timeZone(trigger.clubId())).toLocalDate())
                    .value("count", trigger.number("count", 0)).value("oldest_days", trigger.number("oldestDays", 0))
                    .value("entityId", trigger.clubId()).build());
            case "RemittanceReminderDue" -> Optional.of(NotificationFacts.builder()
                    .occurrence("N-41:" + trigger.text("period"))
                    .value("period", java.time.YearMonth.parse(trigger.text("period")))
                    .value("pending_count", trigger.number("pendingMembers", 0)).value("entityId", trigger.clubId()).build());
            case "JobFailed" -> {
                if (!Boolean.TRUE.equals(config.get("jobs.alertAdminsOnFailure", Boolean.class))) { yield Optional.empty(); }
                String job = Objects.toString(trigger.text("job"), "");
                var day = trigger.occurredAt().atZone(configs.timeZone(trigger.clubId())).toLocalDate();
                yield Optional.of(NotificationFacts.builder().occurrence("N-42:" + job + ":" + day)
                        .value("job_name", new NotificationValues.Localized(locale -> messages.getMessage("jobs.name." + job, null, job, locale)))
                        .value("error_count", trigger.number("errorCount", 0)).value("entityId", trigger.aggregateId()).build());
            }
            case "WeekOpened" -> {
                if (!trigger.flag("notified")) { yield Optional.empty(); }
                var target = trigger.text("weekId") != null ? weeks.byId(trigger.text("weekId")) : weeks.find(LocalDate.parse(trigger.text("isoWeekStart")));
                if (target.isEmpty()) { yield Optional.empty(); }
                var builder = NotificationFacts.builder().occurrence("N-33:" + target.get().weekId()).noMembers()
                        .value("week_start", target.get().isoWeekStart()).value("entityId", target.get().weekId());
                // APP + PUSH only: a member without an app account has nothing to receive (as E5-T05).
                recipients.activeWithActiveDog().stream().filter(member -> member.accountId() != null).forEach(member -> builder.member(member.id(), null));
                yield Optional.of(builder.build());
            }
            default -> Optional.empty();
        };
    }

    /**
     * S15 R-15-11, the deferred N-33: a week validated after its booking opened (`now ≥ openedAt`) and not notified yet, with
     * `messaging.notifyWeekOpening`, gets the same `WeekOpened` notice (idempotent per week), then the mark.
     */
    void validated(String eventId, ForeignEvent event, NotificationEngine engine, Clock clock) {
        try (var tenant = TenantContext.open(event.clubId())) {
            if (!Boolean.TRUE.equals(configs.get(event.clubId()).get("messaging.notifyWeekOpening", Boolean.class))) { return; }
            var target = weeks.byId(event.aggregateId()).orElse(null);
            if (target == null || target.openedAt() == null || clock.instant().isBefore(target.openedAt()) || target.openingNotifiedAt() != null
                    || target.activeClasses() == 0) { return; }
            var payload = new LinkedHashMap<String, Object>();
            payload.put("notified", true); payload.put("weekId", target.weekId()); payload.put("isoWeekStart", target.isoWeekStart().toString());
            engine.emit(new NotificationTrigger(eventId, "WeekOpened", event.clubId(), "Week", target.weekId(), clock.instant(), payload, null, null, event.origin()));
            weeks.markNotified(target.weekId());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        /** The durable consumer id of E5-T05's deferred N-33 (never rename). */
        @Bean("notifications.N-33.deferred") DomainEventHandler<ForeignEvent> weekValidated(CommonNotificationFacts facts, ObjectProvider<NotificationEngine> engine, Clock clock) {
            return new DomainEventHandler<>() {
                @Override public String eventType() { return "WeekValidated"; }
                @Override public Class<ForeignEvent> eventClass() { return ForeignEvent.class; }
                @Override public void handle(String id, ForeignEvent event) { facts.validated(id, event, engine.getObject(), clock); }
            };
        }
    }
}

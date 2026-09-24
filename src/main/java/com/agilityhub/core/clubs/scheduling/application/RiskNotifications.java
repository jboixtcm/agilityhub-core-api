package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.SchedulingRecipients;
import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort;
import com.agilityhub.core.clubs.scheduling.domain.SchedulingCatalog;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSession;
import com.agilityhub.core.identity.application.SignupIdentityService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S15 §8 notifications of the risk processes, one outbox consumer per event, idempotent per event and recipient:
 * <ul>
 * <li>`notifications.N-16` (`ClassAtRisk`): «Possible anul·lació de classe» to the owners of the new bookings (APP + EMAIL)
 * and, the first day only, to the ADMINS (APP).</li>
 * <li>`notifications.N-17` (`ClassAutoCancelled`): N-17 to the ADMINS and the class's INSTRUCTORS (APP + EMAIL) on every
 * auto-cancellation, also with 0 registrants (§13-3), plus N-08a to the affected registrants through
 * {@link SchedulingNotifications#autoCancelled}.</li>
 * <li>`notifications.N-54` (`ClassBelowMinimum`, R-15-12b): «Classe amb pocs alumnes» to the class's INSTRUCTORS and the
 * ADMINS (APP + EMAIL); nothing reaches the students.</li>
 * </ul>
 */
@Service
public class RiskNotifications {
    private final ClassSessionService sessions; private final ClassBookingsPort bookings; private final SchedulingRecipients census;
    private final PlanningCatalogAccess catalogs; private final SignupIdentityService identities; private final NotificationAccounts accounts;
    private final SystemNotificationService notifications; private final PlanningContext context; private final SessionProjection projection;
    private final SchedulingNotifications cancellations;
    public RiskNotifications(ClassSessionService sessions, ClassBookingsPort bookings, SchedulingRecipients census, PlanningCatalogAccess catalogs,
            SignupIdentityService identities, NotificationAccounts accounts, SystemNotificationService notifications, PlanningContext context,
            SessionProjection projection, SchedulingNotifications cancellations) {
        this.sessions = sessions; this.bookings = bookings; this.census = census; this.catalogs = catalogs; this.identities = identities;
        this.accounts = accounts; this.notifications = notifications; this.context = context; this.projection = projection;
        this.cancellations = cancellations;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @SuppressWarnings("unchecked")
    public void atRisk(String eventId, SchedulerEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            var c = sessions.require(event.aggregateId()); var payload = event.payload();
            var ids = (List<String>) payload.getOrDefault("newBookingIds", List.of());
            for (var booking : bookings.bookings(ids)) {
                var member = census.member(booking.memberId()).orElse(null); if (member == null) { continue; }
                var account = member.accountId() == null ? null : accounts.find(member.accountId()).orElse(null);
                String language = account == null ? member.locale() : account.locale();
                var variables = variables("N-16", c, language, payload); variables.put("dog_name", census.dog(booking.dogId()).map(SchedulingRecipients.Dog::name).orElse(""));
                String key = eventId + ":" + booking.bookingId();
                if (account != null) { notifications.appOnce(key + ":app", "N-16", account.id(), variables); }
                if (member.email() != null) {
                    if (account != null && member.email().equals(account.email())) { notifications.sendOnce(key + ":email", "N-16", account.id(), variables); }
                    else { notifications.sendApplicantOnce(key + ":email", "N-16", member.email(), language, variables); }
                }
            }
            if (Boolean.TRUE.equals(payload.get("notifyAdmins"))) {
                for (String admin : identities.admins()) {
                    var account = accounts.find(admin).orElse(null); if (account == null) { continue; }
                    notifications.appOnce(eventId + ":staff:" + admin + ":app", "N-16", admin, variables("N-16", c, account.locale(), payload));
                }
            }
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void autoCancelled(String eventId, SchedulerEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            staff(eventId, "N-17", sessions.require(event.aggregateId()), event.payload());
            cancellations.autoCancelled(eventId, event.payload(), event.aggregateId(), context.config().get("classes.minDogs", Integer.class));
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void belowMinimum(String eventId, SchedulerEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            staff(eventId, "N-54", sessions.require(event.aggregateId()), event.payload());
        }
    }

    /** ADMINS and the class's INSTRUCTORS, APP + EMAIL, each in their own language; one row per account and channel. */
    private void staff(String eventId, String code, ClassSession c, Map<String, Object> payload) {
        var recipients = new LinkedHashSet<>(identities.admins());
        for (String memberId : catalogs.instructorMembers(c.instructorIds() == null ? List.of() : c.instructorIds())) {
            census.member(memberId).map(SchedulingRecipients.Member::accountId).ifPresent(recipients::add);
        }
        for (String accountId : recipients) {
            var account = accounts.find(accountId).orElse(null); if (account == null) { continue; }
            var variables = variables(code, c, account.locale(), payload);
            notifications.appOnce(eventId + ":staff:" + accountId + ":app", code, accountId, variables);
            if (account.email() != null) { notifications.sendOnce(eventId + ":staff:" + accountId + ":email", code, accountId, variables); }
        }
    }

    private Locale locale(String tag) {
        return tag != null && Set.of("ca", "es", "en").contains(tag) ? Locale.forLanguageTag(tag) : Locale.forLanguageTag(context.config().club().defaultLocale());
    }
    /** The catalog variables of each code (CATALEG_NOTIFICACIONS), plus `club_name`, `entityId` and `action` like every S06 notice. */
    private Map<String, Object> variables(String code, ClassSession c, String language, Map<String, Object> payload) {
        var locale = locale(language); var config = context.config(); var values = new LinkedHashMap<String, Object>();
        values.put("club_name", config.club().name());
        values.put("class_date", c.startsAt().atZone(projection.zone()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)));
        values.put("class_time", c.startTime());
        if (code.equals("N-16")) {
            values.put("dog_name", "");
            values.put("review_time", config.get("classes.riskReviewTime", String.class));
            values.put("review_day", c.date().getDayOfWeek().getDisplayName(TextStyle.FULL, locale));
            // ICU `select` of the N-16 template: will the class be cancelled automatically on its own day?
            values.put("auto_cancel", Boolean.TRUE.equals(config.get("classes.riskAutoCancelSameDay", Boolean.class)) ? "true" : "false");
        } else {
            if (code.equals("N-54")) {
                values.put("class_description", projection.description(c, locale));
                values.put("ring_name", context.catalog().rings().stream().filter(r -> r.id().equals(c.ringId())).map(SchedulingCatalog.Resource::name).findFirst().orElse(""));
            }
            Object dogs = payload.containsKey("dogsCount") ? payload.get("dogsCount") : payload.get("countedDogs");
            values.put("dogs_count", dogs instanceof Number number ? number.intValue() : 0);
        }
        values.put("entityId", c.id()); values.put("action", "CHANGE_CLASS");
        return values;
    }

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        @Bean("notifications.N-16") DomainEventHandler<SchedulerEvent> atRisk(RiskNotifications service) { return handler("ClassAtRisk", service::atRisk); }
        @Bean("notifications.N-17") DomainEventHandler<SchedulerEvent> autoCancelled(RiskNotifications service) { return handler("ClassAutoCancelled", service::autoCancelled); }
        @Bean("notifications.N-54") DomainEventHandler<SchedulerEvent> belowMinimum(RiskNotifications service) { return handler("ClassBelowMinimum", service::belowMinimum); }
        private static DomainEventHandler<SchedulerEvent> handler(String type, java.util.function.BiConsumer<String, SchedulerEvent> delivery) {
            return new DomainEventHandler<>() {
                @Override public String eventType() { return type; }
                @Override public Class<SchedulerEvent> eventClass() { return SchedulerEvent.class; }
                @Override public void handle(String id, SchedulerEvent event) { delivery.accept(id, event); }
            };
        }
    }
}

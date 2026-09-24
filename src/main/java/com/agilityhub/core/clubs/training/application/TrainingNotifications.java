package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.census.application.TrainingMemberAccess;
import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.clubs.training.domain.*;
import com.agilityhub.core.clubs.training.persistence.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import java.time.format.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * S09 §8 / CATALEG_NOTIFICACIONS, produced only by outbox consumers (durable names `notifications.N-06` …), to the
 * booking member: N-06 `TrainingBooked` (any origin) → APP; N-47 `TrainingBooked/Cancelled` made by the club
 * (`origin = BACKOFFICE` or `by = ADMIN`) → APP + EMAIL + SMS intent (QUEUED, or SKIPPED_MODULE_OFF without SMS);
 * N-07 `TrainingCancelled` → APP unless N-47 applies or `cancelReason = MEMBER_LEFT`. Rendered in the recipient's
 * locale and the club's time zone; notification ids are `eventId:memberId:channel`, so a redelivery creates nothing.
 */
@Service
public class TrainingNotifications {
    private final TrainingBookingRepository bookings; private final TrainingMemberAccess census; private final NotificationAccounts accounts;
    private final SystemNotificationService notifications; private final TrainingContext context; private final IcuMessageSource messages;
    private final TrainingBookingService service;
    public TrainingNotifications(TrainingBookingRepository bookings, TrainingMemberAccess census, NotificationAccounts accounts,
            SystemNotificationService notifications, TrainingContext context, IcuMessageSource messages, TrainingBookingService service) {
        this.bookings = bookings; this.census = census; this.accounts = accounts; this.notifications = notifications; this.context = context;
        this.messages = messages; this.service = service;
    }
    /** Which catalog code (if any) an event produces for a handler. */
    static Optional<String> code(String handler, TrainingEvent event) {
        var p = event.payload(); boolean booked = event.kind() == TrainingEvent.Kind.TrainingBooked;
        boolean club = "BACKOFFICE".equals(Objects.toString(p.get("origin"), "")) || "ADMIN".equals(Objects.toString(p.get("by"), ""));
        return Optional.ofNullable(switch (handler) {
            case "N-06" -> booked ? "N-06" : null;
            case "N-47" -> club ? "N-47" : null;
            case "N-07" -> !booked && !club && !"MEMBER_LEFT".equals(Objects.toString(p.get("cancelReason"), "")) ? "N-07" : null;
            default -> null;
        });
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deliver(String eventId, String handler, TrainingEvent event) {
        var code = code(handler, event); if (code.isEmpty()) { return; }
        try (var tenant = TenantContext.open(event.clubId())) {
            var booking = bookings.findById(event.aggregateId()).orElse(null); if (booking == null) { return; }
            var member = census.member(booking.memberId()).orElse(null); if (member == null) { return; }
            var account = member.accountId() == null ? null : accounts.find(member.accountId()).orElse(null);
            var locale = locale(account == null ? member.locale() : account.locale());
            var variables = variables(code.get(), event, booking, locale);
            String key = eventId + ":" + member.id();
            if (account != null) { notifications.appOnce(key + ":app", code.get(), account.id(), variables); }
            if (!code.get().equals("N-47")) { return; }
            if (member.email() != null) {
                if (account != null && member.email().equals(account.email())) { notifications.sendOnceLocalized(key + ":email", code.get(), account.id(), locale.toLanguageTag(), variables); }
                else { notifications.sendApplicantOnce(key + ":email", code.get(), member.email(), locale.toLanguageTag(), variables); }
            }
            if (!member.phones().isEmpty()) {
                notifications.smsIntentOnce(key + ":sms", code.get(), member.accountId(), locale.toLanguageTag(), member.phones(),
                        TrainingSms.compact(messages.format("notif.N-47.sms", variables, locale)), context.enabled(Module.SMS), variables);
            }
        }
    }
    private Locale locale(String tag) {
        return tag != null && Set.of("ca", "es", "en").contains(tag) ? Locale.forLanguageTag(tag) : Locale.forLanguageTag(context.config().club().defaultLocale());
    }
    private Map<String, Object> variables(String code, TrainingEvent event, TrainingBooking b, Locale locale) {
        var zone = context.zone(); var start = b.startsAt().atZone(zone); var end = b.endsAt().atZone(zone); var time = DateTimeFormatter.ofPattern("H:mm");
        var values = new LinkedHashMap<String, Object>();
        values.put("club_name", context.config().club().name());
        values.put("dog_name", census.dog(b.dogId()).map(TrainingMemberAccess.Dog::name).orElse(""));
        values.put("date", start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)));
        values.put("time", start.format(time) + "–" + end.format(time)); // «8:30–9:00»
        values.put("ring_name", Objects.toString(service.ringNames().get(b.ringId()), ""));
        if (code.equals("N-47")) {
            values.put("change", event.kind() == TrainingEvent.Kind.TrainingBooked ? "BOOKED" : "CANCELLED");
            String note = event.kind() == TrainingEvent.Kind.TrainingCancelled ? b.cancelNote() : null;
            values.put("has_admin_text", note != null && !note.isBlank()); values.put("admin_text", note == null ? "" : note);
            values.put("action", "OPEN_BOOKING");
        }
        values.put("entityId", b.id());
        return values;
    }

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        @Bean("notifications.N-06") DomainEventHandler<TrainingEvent> n06(TrainingNotifications s) { return handler("TrainingBooked", "N-06", s); }
        @Bean("notifications.N-07") DomainEventHandler<TrainingEvent> n07(TrainingNotifications s) { return handler("TrainingCancelled", "N-07", s); }
        @Bean("notifications.N-47") DomainEventHandler<TrainingEvent> n47Booked(TrainingNotifications s) { return handler("TrainingBooked", "N-47", s); }
        @Bean("notifications.N-47.cancelled") DomainEventHandler<TrainingEvent> n47Cancelled(TrainingNotifications s) { return handler("TrainingCancelled", "N-47", s); }
        private DomainEventHandler<TrainingEvent> handler(String type, String code, TrainingNotifications service) {
            return new DomainEventHandler<>() {
                public String eventType() { return type; } public Class<TrainingEvent> eventClass() { return TrainingEvent.class; }
                public void handle(String id, TrainingEvent event) { service.deliver(id, code, event); }
            };
        }
    }
}

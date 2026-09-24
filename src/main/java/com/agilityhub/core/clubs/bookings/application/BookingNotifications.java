package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import java.time.format.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * S08 §8 / CATALEG_NOTIFICACIONS, produced only by outbox consumers (durable names `notifications.N-04` …):
 * N-04 `BookingCreated{origin ∈ APP, INSTRUCTOR}` → APP to the dog's owner and to the group member who booked (A20b);
 * N-05 `BookingCancelled{by ∈ MEMBER, INSTRUCTOR}` → APP (the instructor's «ha avisat» has no SMS, §13-7);
 * N-36 BACKOFFICE creation/cancellation → APP + EMAIL + SMS intent (QUEUED, or SKIPPED_MODULE_OFF without SMS);
 * N-40 `BookingCancelled{reason: PAYMENT_TIMEOUT}` → APP + EMAIL, except with `checkoutFailed` (E30). Rendered in the recipient's locale and the club's
 * time zone; notification ids are `eventId:memberId:channel`, so a redelivered event creates nothing new.
 */
@Service
public class BookingNotifications {
    private final BookingRepository bookings; private final BookingMemberAccess census; private final ClassSessionBookingAccess classes;
    private final NotificationAccounts accounts; private final SystemNotificationService notifications; private final BookingContext context;
    private final BookingViews views; private final IcuMessageSource messages;
    public BookingNotifications(BookingRepository bookings, BookingMemberAccess census, ClassSessionBookingAccess classes, NotificationAccounts accounts,
            SystemNotificationService notifications, BookingContext context, BookingViews views, IcuMessageSource messages) {
        this.bookings = bookings; this.census = census; this.classes = classes; this.accounts = accounts; this.notifications = notifications;
        this.context = context; this.views = views; this.messages = messages;
    }
    /** Which catalog code (if any) an event produces. */
    static Optional<String> code(String handler, BookingEvent event) {
        var p = event.payload(); String origin = Objects.toString(p.get("origin"), ""), by = Objects.toString(p.get("by"), ""), reason = Objects.toString(p.get("reason"), "");
        boolean created = event.kind() == BookingEvent.Kind.BookingCreated;
        return Optional.ofNullable(switch (handler) {
            case "N-04" -> created && Set.of("APP", "INSTRUCTOR").contains(origin) ? "N-04" : null;
            case "N-05" -> !created && Set.of("MEMBER", "INSTRUCTOR").contains(by) && !"BACKOFFICE".equals(origin) && !"PAYMENT_TIMEOUT".equals(reason) ? "N-05" : null;
            case "N-36" -> "BACKOFFICE".equals(origin) ? "N-36" : null;
            // E30: a checkout the provider failed to open was already reported to the member as an error.
            case "N-40" -> !created && "PAYMENT_TIMEOUT".equals(reason) && !Boolean.TRUE.equals(p.get("checkoutFailed")) ? "N-40" : null;
            default -> null;
        });
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deliver(String eventId, String handler, BookingEvent event) {
        var code = code(handler, event); if (code.isEmpty()) { return; }
        try (var tenant = TenantContext.open(event.clubId())) {
            var booking = bookings.findById(event.aggregateId()).orElse(null); if (booking == null) { return; }
            var session = classes.find(booking.classSessionId()).orElse(null); if (session == null) { return; }
            var recipients = new LinkedHashSet<String>(); recipients.add(booking.memberId());
            if (code.get().equals("N-04")) {
                census.memberByAccount(booking.bookedBy().accountId()).map(BookingMemberAccess.Member::id).ifPresent(recipients::add);
            }
            for (String memberId : recipients) { send(eventId, code.get(), event, booking, session, memberId); }
        }
    }
    private void send(String eventId, String code, BookingEvent event, Booking booking, ClassSessionBookingAccess.Session session, String memberId) {
        var member = census.member(memberId).orElse(null); if (member == null) { return; }
        var account = member.accountId() == null ? null : accounts.find(member.accountId()).orElse(null);
        String language = account == null ? member.locale() : account.locale(); var locale = locale(language);
        var variables = variables(code, event, booking, session, locale);
        String key = eventId + ":" + memberId;
        if (account != null) { notifications.appOnce(key + ":app", code, account.id(), variables); }
        if (Set.of("N-36", "N-40").contains(code) && member.email() != null) {
            if (account != null && member.email().equals(account.email())) { notifications.sendOnceLocalized(key + ":email", code, account.id(), locale.toLanguageTag(), variables); }
            else { notifications.sendApplicantOnce(key + ":email", code, member.email(), locale.toLanguageTag(), variables); }
        }
        if (code.equals("N-36") && !member.phones().isEmpty()) {
            notifications.smsIntentOnce(key + ":sms", code, member.accountId(), locale.toLanguageTag(), member.phones(),
                    BookingSms.compact(messages.format("notif.N-36.sms", variables, locale)), context.enabled(Module.SMS), variables);
        }
    }
    private Locale locale(String tag) {
        return tag != null && Set.of("ca", "es", "en").contains(tag) ? Locale.forLanguageTag(tag) : Locale.forLanguageTag(context.config().club().defaultLocale());
    }
    private Map<String, Object> variables(String code, BookingEvent event, Booking b, ClassSessionBookingAccess.Session session, Locale locale) {
        var labels = classes.labels(session, locale); var start = b.classStartsAt().atZone(context.zone()); var values = new LinkedHashMap<String, Object>();
        values.put("club_name", context.config().club().name());
        values.put("dog_name", census.dog(b.dogId()).map(BookingMemberAccess.Dog::name).orElse(""));
        values.put("class_date", start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)));
        values.put("class_time", start.toLocalTime().toString());
        values.put("class_description", labels.description()); values.put("ring_name", labels.ringName() == null ? "" : labels.ringName());
        if (code.equals("N-04")) { values.put("calendar_links", views.calendarLinks(b, labels).get("ics")); }
        if (code.equals("N-05")) { values.put("late", Boolean.TRUE.equals(event.payload().get("late"))); }
        if (code.equals("N-36")) {
            values.put("actor", messages.format("notif.N-36.actor", Map.of(), locale));
            values.put("change", event.kind() == BookingEvent.Kind.BookingCreated ? "BOOKED" : "CANCELLED");
        }
        values.put("entityId", b.id()); if (!code.equals("N-05")) { values.put("action", "OPEN_BOOKING"); }
        return values;
    }

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        @Bean("notifications.N-04") DomainEventHandler<BookingEvent> n04(BookingNotifications s) { return handler("BookingCreated", "N-04", s); }
        @Bean("notifications.N-05") DomainEventHandler<BookingEvent> n05(BookingNotifications s) { return handler("BookingCancelled", "N-05", s); }
        @Bean("notifications.N-36") DomainEventHandler<BookingEvent> n36Created(BookingNotifications s) { return handler("BookingCreated", "N-36", s); }
        @Bean("notifications.N-36.cancelled") DomainEventHandler<BookingEvent> n36Cancelled(BookingNotifications s) { return handler("BookingCancelled", "N-36", s); }
        @Bean("notifications.N-40") DomainEventHandler<BookingEvent> n40(BookingNotifications s) { return handler("BookingCancelled", "N-40", s); }
        private DomainEventHandler<BookingEvent> handler(String type, String code, BookingNotifications service) {
            return new DomainEventHandler<>() {
                public String eventType() { return type; } public Class<BookingEvent> eventClass() { return BookingEvent.class; }
                public void handle(String id, BookingEvent event) { service.deliver(id, code, event); }
            };
        }
    }
}

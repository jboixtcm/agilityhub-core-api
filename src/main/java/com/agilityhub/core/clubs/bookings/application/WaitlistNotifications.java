package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import java.time.Instant;
import java.time.format.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * S08 §8 waiting-list notifications, produced only by outbox consumers:
 * <ul>
 * <li>`notifications.N-15` on `WaitlistNotified` → «S'ha alliberat una plaça!» to the owner of each notified entry: APP
 * + SMS intent (QUEUED, ≤ 160 GSM-7, or SKIPPED_MODULE_OFF without SMS) + PUSH intent (QUEUED, or SKIPPED_MODULE_OFF
 * without PUSH), action CLAIM_SEAT; ids `eventId:entryId:channel`, so one N-15 per entry and offer.</li>
 * <li>`notifications.N-46` on `WaitlistConsolidated`, `notifications.N-46.booked` on `BookingCreated` and
 * `notifications.N-46.held` on `SeatHoldReleased` (the PAY_TO_BOOK seat taken as PAYMENT_PENDING) (ALL_AT_ONCE):
 * a read-only check first (nothing offered → nothing to do), the idempotent demotion of R-08-13 in its own transaction
 * only while an entry is still NOTIFIED (the booking transaction normally did it already), then «La plaça ja s'ha
 * ocupat» → APP to every ACTIVE entry whose offer was taken (ACTIVE with `notifiedAt`) and whose N-15 of that offer
 * was delivered; ids
 * `N-46:entryId:notifiedAt:app`, so each lost offer is told exactly once, whichever event gets there first.</li>
 * </ul>
 * Rendered in the recipient's locale with the club time zone.
 */
@Service
public class WaitlistNotifications {
    private final WaitlistEntryRepository waitlist; private final BookingMemberAccess census; private final ClassSessionBookingAccess classes;
    private final NotificationAccounts accounts; private final SystemNotificationService notifications; private final BookingContext context;
    private final IcuMessageSource messages; private final WaitlistTransitions transitions; private final BookingTransactions transactions;
    private final SeatLockRepository locks;
    public WaitlistNotifications(WaitlistEntryRepository waitlist, BookingMemberAccess census, ClassSessionBookingAccess classes, NotificationAccounts accounts,
            SystemNotificationService notifications, BookingContext context, IcuMessageSource messages, WaitlistTransitions transitions,
            BookingTransactions transactions, SeatLockRepository locks) {
        this.waitlist = waitlist; this.census = census; this.classes = classes; this.accounts = accounts; this.notifications = notifications;
        this.context = context; this.messages = messages; this.transitions = transitions; this.transactions = transactions; this.locks = locks;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void offered(String eventId, BookingEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            var ids = event.payload().get("entryIds") instanceof Collection<?> list ? list.stream().map(Object::toString).toList() : List.<String>of();
            for (var entry : waitlist.byIds(ids)) {
                if (entry.state() != WaitlistState.NOTIFIED) { continue; } // taken, left or demoted before the delivery
                send(eventId + ":" + entry.id(), "N-15", entry);
            }
        }
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void seatTaken(String eventId, BookingEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            if (!context.enabled(Module.WAITLIST) || context.waitlistMode() != WaitlistMode.ALL_AT_ONCE) { return; }
            String classId = event.kind() == BookingEvent.Kind.WaitlistConsolidated
                    ? waitlist.findById(event.aggregateId()).map(WaitlistEntry::classSessionId).orElse(null)
                    : Objects.toString(event.payload().get("classId"), null);
            if (classId == null) { return; }
            // Read-only first (E5-T08): an ordinary booking (no open offer, no taken one) stops here, without a write
            // transaction or the class's seat lock. The lock is taken only while a NOTIFIED entry may still need demoting.
            var live = waitlist.live(classId);
            if (live.stream().noneMatch(e -> e.notifiedAt() != null)) { return; }
            if (live.stream().anyMatch(e -> e.state() == WaitlistState.NOTIFIED)) {
                transactions.write(List.of(classId), () -> { locks.lock(classId); return transitions.demoteIfFull(classId, BookingActor.system()); });
                live = waitlist.live(classId);
            }
            for (var entry : live) {
                if (entry.state() != WaitlistState.ACTIVE || entry.notifiedAt() == null || !offerDelivered(entry)) { continue; }
                send("N-46:" + entry.id() + ":" + entry.notifiedAt().toEpochMilli(), "N-46", entry);
            }
        }
    }
    /**
     * R-08-13: «La plaça ja s'ha ocupat» only to whom heard of the offer. When the outbox delivers `WaitlistNotified`
     * after the seat was taken, N-15 skips the demoted entries, so there is no N-15 row of that offer (created at or
     * after its `notifiedAt`) and no N-46 either.
     */
    private boolean offerDelivered(WaitlistEntry entry) {
        var member = census.member(entry.memberId()).orElse(null);
        return member != null && member.accountId() != null && notifications.appSentSince("N-15", member.accountId(), entry.id(), entry.notifiedAt());
    }

    private void send(String key, String code, WaitlistEntry entry) {
        var session = classes.find(entry.classSessionId()).orElse(null); if (session == null) { return; }
        var member = census.member(entry.memberId()).orElse(null); if (member == null) { return; }
        var account = member.accountId() == null ? null : accounts.find(member.accountId()).orElse(null);
        var locale = locale(account == null ? member.locale() : account.locale());
        var variables = variables(code, entry, session, locale);
        if (account != null) { notifications.appOnce(key + ":app", code, account.id(), variables); }
        if (!code.equals("N-15")) { return; }
        if (!member.phones().isEmpty()) {
            notifications.smsIntentOnce(key + ":sms", code, member.accountId(), locale.toLanguageTag(), member.phones(),
                    BookingSms.compact(messages.format("notif.N-15.sms", variables, locale)), context.enabled(Module.SMS), variables);
        }
        if (account != null) { notifications.pushIntentOnce(key + ":push", code, account.id(), locale.toLanguageTag(), context.enabled(Module.PUSH), variables); }
    }
    private Locale locale(String tag) {
        return tag != null && Set.of("ca", "es", "en").contains(tag) ? Locale.forLanguageTag(tag) : Locale.forLanguageTag(context.config().club().defaultLocale());
    }
    private Map<String, Object> variables(String code, WaitlistEntry e, ClassSessionBookingAccess.Session session, Locale locale) {
        var start = session.startsAt().atZone(context.zone()); var values = new LinkedHashMap<String, Object>();
        values.put("club_name", context.config().club().name());
        values.put("dog_name", census.dog(e.dogId()).map(BookingMemberAccess.Dog::name).orElse(""));
        values.put("class_date", start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)));
        values.put("class_time", start.toLocalTime().toString());
        if (code.equals("N-15")) {
            // `mode` only selects the wording (like N-36's `change`); `confirm_by` exists in FIFO only (catalog).
            values.put("mode", e.confirmBy() == null ? WaitlistMode.ALL_AT_ONCE.name() : WaitlistMode.FIFO.name());
            if (e.confirmBy() != null) { values.put("confirm_by", localTime(e.confirmBy())); }
            values.put("action", "CLAIM_SEAT");
        }
        values.put("entityId", e.id());
        return values;
    }
    private String localTime(Instant instant) { return instant.atZone(context.zone()).toLocalTime().withSecond(0).withNano(0).toString(); }

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        @Bean("notifications.N-15") DomainEventHandler<BookingEvent> n15(WaitlistNotifications s) { return handler("WaitlistNotified", s::offered); }
        @Bean("notifications.N-46") DomainEventHandler<BookingEvent> n46(WaitlistNotifications s) { return handler("WaitlistConsolidated", s::seatTaken); }
        @Bean("notifications.N-46.booked") DomainEventHandler<BookingEvent> n46Booked(WaitlistNotifications s) { return handler("BookingCreated", s::seatTaken); }
        // R-08-13/R-08-18 (E5-T08): a PAY_TO_BOOK booking takes the seat as PAYMENT_PENDING and emits BookingCreated only
        // when paid, so the confirmation's SeatHoldReleased carries the demotion notice at the moment the seat is taken.
        @Bean("notifications.N-46.held") DomainEventHandler<BookingEvent> n46Held(WaitlistNotifications s) { return handler("SeatHoldReleased", s::seatTaken); }
        private DomainEventHandler<BookingEvent> handler(String type, java.util.function.BiConsumer<String, BookingEvent> action) {
            return new DomainEventHandler<>() {
                public String eventType() { return type; } public Class<BookingEvent> eventClass() { return BookingEvent.class; }
                public void handle(String id, BookingEvent event) { action.accept(id, event); }
            };
        }
    }
}

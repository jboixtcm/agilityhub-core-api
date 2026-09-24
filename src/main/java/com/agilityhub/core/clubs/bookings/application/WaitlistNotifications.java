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
 * without PUSH), action CLAIM_SEAT; ids `eventId:entryId:channel`, so one N-15 per entry and offer. Each entry is one
 * transaction: while the entry is still NOTIFIED with that `notifiedAt`, the N-15 rows are written first and then
 * `offerNotifiedAt` says «the N-15 rows of this offer were written», only when at least one row was queued; an entry
 * demoted (or taken) first gets neither (E5-T11, E5-T14).</li>
 * <li>`notifications.N-46` on `WaitlistConsolidated`, `notifications.N-46.booked` on `BookingCreated` and
 * `notifications.N-46.held` on `SeatHoldReleased` of a confirmation (the PAY_TO_BOOK seat taken as PAYMENT_PENDING; a
 * hold released without a booking of its dog is ignored, and an expired hold emits no event) (ALL_AT_ONCE):
 * a read-only check first (nothing offered → nothing to do), the idempotent demotion of R-08-13 in its own transaction
 * only while an entry is still NOTIFIED (the booking transaction normally did it already), then «La plaça ja s'ha
 * ocupat» → APP to every ACTIVE entry whose offer was taken (ACTIVE with `notifiedAt`) and whose N-15 rows of that
 * offer were written (`offerNotifiedAt` = `notifiedAt`; a FIFO offer that expired is EXPIRED, not live, and its mark
 * is cleared, E5-T14); ids
 * `N-46:entryId:notifiedAt:app`, so each lost offer is told exactly once, whichever event gets there first.</li>
 * </ul>
 * Rendered in the recipient's locale with the club time zone.
 */
@Service
public class WaitlistNotifications {
    private final WaitlistEntryRepository waitlist; private final BookingMemberAccess census; private final ClassSessionBookingAccess classes;
    private final NotificationAccounts accounts; private final SystemNotificationService notifications; private final BookingContext context;
    private final IcuMessageSource messages; private final WaitlistTransitions transitions; private final BookingTransactions transactions;
    private final SeatLockRepository locks; private final BookingRepository bookings;
    public WaitlistNotifications(WaitlistEntryRepository waitlist, BookingMemberAccess census, ClassSessionBookingAccess classes, NotificationAccounts accounts,
            SystemNotificationService notifications, BookingContext context, IcuMessageSource messages, WaitlistTransitions transitions,
            BookingTransactions transactions, SeatLockRepository locks, BookingRepository bookings) {
        this.waitlist = waitlist; this.census = census; this.classes = classes; this.accounts = accounts; this.notifications = notifications;
        this.context = context; this.messages = messages; this.transitions = transitions; this.transactions = transactions; this.locks = locks;
        this.bookings = bookings;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void offered(String eventId, BookingEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            var ids = event.payload().get("entryIds") instanceof Collection<?> list ? list.stream().map(Object::toString).toList() : List.<String>of();
            for (var entry : waitlist.byIds(ids)) {
                if (entry.state() != WaitlistState.NOTIFIED) { continue; } // taken, left or demoted before the delivery
                // R-08-13 (E5-T11, E5-T14): the N-15 rows are written first and the entry records them after, in the same
                // transaction, only when at least one row was queued (a member without account or phone gets none). A
                // demotion committed first is seen by the re-read (no N-15, no N-46); one running meanwhile conflicts.
                transactions.write(List.of(entry.classSessionId()), () -> {
                    var current = waitlist.findById(entry.id()).filter(e -> e.state() == WaitlistState.NOTIFIED && entry.notifiedAt().equals(e.notifiedAt()));
                    if (current.isEmpty() || send(eventId + ":" + entry.id(), "N-15", entry, true) == 0) { return null; }
                    if (!waitlist.markOfferNotified(entry.id(), entry.notifiedAt())) {
                        throw new IllegalStateException("Waiting-list entry " + entry.id() + " changed while its N-15 was written"); // rolls the rows back
                    }
                    return null;
                });
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
            // E5-T11: a hold released without a booking (DELETE /seat-holds) takes no seat; only a confirmation's does.
            if (event.kind() == BookingEvent.Kind.SeatHoldReleased
                    && bookings.live(classId, Objects.toString(event.payload().get("dogId"), "")).isEmpty()) { return; }
            // Read-only first (E5-T08): an ordinary booking (no open offer, no taken one) stops here, without a write
            // transaction or the class's seat lock. The lock is taken only while a NOTIFIED entry may still need demoting.
            var live = waitlist.live(classId);
            if (live.stream().noneMatch(e -> e.notifiedAt() != null)) { return; }
            if (live.stream().anyMatch(e -> e.state() == WaitlistState.NOTIFIED)) {
                transactions.write(List.of(classId), () -> { locks.lock(classId); return transitions.demoteIfFull(classId, BookingActor.system()); });
                live = waitlist.live(classId);
            }
            for (var entry : live) {
                if (entry.state() != WaitlistState.ACTIVE || entry.notifiedAt() == null || !entry.notifiedAt().equals(entry.offerNotifiedAt())) { continue; }
                send("N-46:" + entry.id() + ":" + entry.notifiedAt().toEpochMilli(), "N-46", entry, false);
            }
        }
    }
    /**
     * Writes the notice rows (each id idempotent) and returns how many channel rows now exist for this notice: 0 when
     * the class or the member is gone, or the member has neither an account nor a phone. `joined` = inside the caller's
     * transaction (the MANDATORY variants); otherwise each row gets its own (the NEVER methods).
     */
    private int send(String key, String code, WaitlistEntry entry, boolean joined) {
        var session = classes.find(entry.classSessionId()).orElse(null); if (session == null) { return 0; }
        var member = census.member(entry.memberId()).orElse(null); if (member == null) { return 0; }
        var account = member.accountId() == null ? null : accounts.find(member.accountId()).orElse(null);
        var locale = locale(account == null ? member.locale() : account.locale());
        var variables = variables(code, entry, session, locale);
        int rows = 0;
        if (account != null) {
            if (joined) { notifications.appOnceInTransaction(key + ":app", code, account.id(), variables); } else { notifications.appOnce(key + ":app", code, account.id(), variables); }
            rows++;
        }
        if (!code.equals("N-15")) { return rows; }
        if (!member.phones().isEmpty()) {
            var body = BookingSms.compact(messages.format("notif.N-15.sms", variables, locale));
            if (joined) { notifications.smsIntentOnceInTransaction(key + ":sms", code, member.accountId(), locale.toLanguageTag(), member.phones(), body, context.enabled(Module.SMS), variables); }
            else { notifications.smsIntentOnce(key + ":sms", code, member.accountId(), locale.toLanguageTag(), member.phones(), body, context.enabled(Module.SMS), variables); }
            rows++;
        }
        if (account != null) {
            if (joined) { notifications.pushIntentOnceInTransaction(key + ":push", code, account.id(), locale.toLanguageTag(), context.enabled(Module.PUSH), variables); }
            else { notifications.pushIntentOnce(key + ":push", code, account.id(), locale.toLanguageTag(), context.enabled(Module.PUSH), variables); }
            rows++;
        }
        return rows;
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

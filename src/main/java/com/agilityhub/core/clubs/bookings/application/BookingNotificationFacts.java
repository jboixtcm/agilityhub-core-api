package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.WaitlistMode;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationValues;
import com.agilityhub.core.clubs.messaging.application.ports.WaitlistRelevancePort;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.IcuMessageSource;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * S08/S10 notices explained to the S11 engine (E7-T02; replaces `BookingNotifications`, `WaitlistNotifications` and
 * `NoShowNotifications`), with their E5/E6 rules unchanged:
 * <ul>
 * <li>`BookingCreated`: N-04 for `origin ∈ {APP, INSTRUCTOR}` to the dog's owner and to the group member who booked
 * (A20b); N-36 for `BACKOFFICE`; N-46 when the booking took the seat of an ALL_AT_ONCE offer.</li>
 * <li>`BookingCancelled`: N-05 for `by ∈ {MEMBER, INSTRUCTOR}` (the «ha avisat» of 21/D12 is origin INSTRUCTOR whoever saves
 * it, R-08-19: never N-36), N-36 for `BACKOFFICE`, N-40 for `PAYMENT_TIMEOUT` without `checkoutFailed` (E30).</li>
 * <li>`WaitlistNotified` → N-15 to each entry still `NOTIFIED` with that offer (action CLAIM_SEAT, `mode`, `confirm_by`
 * in FIFO); once stored, in the same transaction, `offerNotifiedAt` records «the N-15 of this offer reached the member»
 * (a channel was delivered or queued; E5-T11, E5-T14), and an entry changed meanwhile rolls the notices back.</li>
 * <li>`WaitlistConsolidated`, `BookingCreated`, `SeatHoldReleased` of a confirmation → N-46 (ALL_AT_ONCE): the idempotent
 * demotion of R-08-13 first when an entry is still `NOTIFIED`, then every `ACTIVE` entry whose delivered offer was taken,
 * once per offer (`N-46:{entryId}:{notifiedAt}`), whichever event arrives first.</li>
 * <li>`NoShowNoticeDue` → N-19 per attendance of the batch, `class_date` = the class's own date (S10 §8) and
 * `class_description` in the recipient's language (E65); the first delivered channel writes `noShowNotice.sentAt` (S10 §7
 * «avís ja enviat»). The e-mail follows the owner's `PERSONAL` preference through the engine's `ChannelResolver`.</li>
 * <li>`ReminderDue` of a class booking → N-13 (`kind = CLASS`).</li>
 * </ul>
 */
@Service
public class BookingNotificationFacts implements NotificationFactsPort, WaitlistRelevancePort {
    private static final Set<String> TYPES = Set.of("BookingCreated", "BookingCancelled", "WaitlistNotified", "WaitlistConsolidated", "SeatHoldReleased",
            "NoShowNoticeDue", "ReminderDue");
    private final BookingRepository bookings; private final WaitlistEntryRepository waitlist; private final AttendanceRepository attendances;
    private final BookingMemberAccess census; private final ClassSessionBookingAccess classes; private final BookingContext context; private final BookingViews views;
    private final IcuMessageSource messages; private final WaitlistTransitions transitions; private final BookingTransactions transactions; private final SeatLockRepository locks;

    public BookingNotificationFacts(BookingRepository bookings, WaitlistEntryRepository waitlist, AttendanceRepository attendances, BookingMemberAccess census,
            ClassSessionBookingAccess classes, BookingContext context, BookingViews views, IcuMessageSource messages, WaitlistTransitions transitions,
            BookingTransactions transactions, SeatLockRepository locks) {
        this.bookings = bookings; this.waitlist = waitlist; this.attendances = attendances; this.census = census; this.classes = classes; this.context = context;
        this.views = views; this.messages = messages; this.transitions = transitions; this.transactions = transactions; this.locks = locks;
    }

    @Override public Set<String> eventTypes() { return TYPES; }

    @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
        return switch (code) {
            case "N-04", "N-05", "N-36", "N-40" -> booking(trigger, code);
            case "N-15" -> offered(trigger);
            case "N-46" -> seatTaken(trigger);
            case "N-19" -> noShow(trigger);
            case "N-13" -> reminder(trigger);
            default -> Optional.empty();
        };
    }

    /** Which catalog code (if any) a booking event produces (S08 §8, R-08-19, E30). */
    static boolean applies(String code, NotificationTrigger trigger) {
        String origin = Objects.toString(trigger.text("origin"), ""), by = Objects.toString(trigger.text("by"), ""), reason = Objects.toString(trigger.text("reason"), "");
        boolean created = "BookingCreated".equals(trigger.type());
        return switch (code) {
            case "N-04" -> created && Set.of("APP", "INSTRUCTOR").contains(origin);
            case "N-05" -> !created && (Set.of("MEMBER", "INSTRUCTOR").contains(by) || "INSTRUCTOR".equals(origin)) && !"BACKOFFICE".equals(origin)
                    && !"PAYMENT_TIMEOUT".equals(reason);
            case "N-36" -> "BACKOFFICE".equals(origin);
            case "N-40" -> !created && "PAYMENT_TIMEOUT".equals(reason) && !trigger.flag("checkoutFailed");
            default -> false;
        };
    }

    private Optional<NotificationFacts> booking(NotificationTrigger trigger, String code) {
        if (!applies(code, trigger)) { return Optional.empty(); }
        var booking = bookings.findById(Objects.requireNonNullElse(trigger.text("bookingId"), trigger.aggregateId())).orElse(null);
        if (booking == null) { return Optional.empty(); }
        var session = classes.find(booking.classSessionId()).orElse(null);
        if (session == null) { return Optional.empty(); }
        var builder = classValues(NotificationFacts.builder(), session, booking.classStartsAt())
                .subject(NotificationSubject.booking(booking.id()).with(NotificationSubject.classSession(booking.classSessionId())));
        var subject = NotificationSubject.booking(booking.id());
        builder.value("entityId", booking.id()).member(new NotificationFacts.MemberSubject(booking.memberId(), booking.dogId(), Map.of(), subject));
        if (code.equals("N-04") && booking.bookedBy() != null && booking.bookedBy().accountId() != null) {
            census.memberByAccount(booking.bookedBy().accountId()).map(BookingMemberAccess.Member::id).filter(id -> !id.equals(booking.memberId()))
                    .ifPresent(booker -> builder.member(new NotificationFacts.MemberSubject(booker, booking.dogId(), Map.of(), subject)));
        }
        switch (code) {
            case "N-04" -> builder.value("calendar_links", views.calendarLinks(booking, classes.labels(session, Locale.forLanguageTag(context.config().club().defaultLocale()))).get("ics"));
            case "N-05" -> builder.value("late", trigger.flag("late"));
            case "N-36" -> builder.value("actor", new NotificationValues.Localized(locale -> messages.format("notif.N-36.actor", Map.of(), locale)))
                    .value("change", "BookingCreated".equals(trigger.type()) ? "BOOKED" : "CANCELLED");
            default -> { }
        }
        return Optional.of(builder.build());
    }

    private Optional<NotificationFacts> offered(NotificationTrigger trigger) {
        var entries = waitlist.byIds(trigger.ids("entryIds")).stream().filter(e -> e.state() == WaitlistState.NOTIFIED && e.notifiedAt() != null)
                .sorted(Comparator.comparing(WaitlistEntry::id)).toList();
        if (entries.isEmpty()) { return Optional.empty(); }
        var session = classes.find(entries.getFirst().classSessionId()).orElse(null);
        if (session == null) { return Optional.empty(); }
        var builder = classValues(NotificationFacts.builder(), session, session.startsAt());
        for (var entry : entries) {
            // `mode` only selects the wording; `confirm_by` exists in FIFO only (catalog).
            var values = new java.util.LinkedHashMap<String, Object>();
            values.put("mode", entry.confirmBy() == null ? WaitlistMode.ALL_AT_ONCE.name() : WaitlistMode.FIFO.name());
            if (entry.confirmBy() != null) { values.put("confirm_by", entry.confirmBy()); }
            values.put("entityId", entry.id());
            builder.member(new NotificationFacts.MemberSubject(entry.memberId(), entry.dogId(), values,
                    NotificationSubject.waitlistEntry(entry.id()).with(NotificationSubject.classSession(entry.classSessionId()))));
        }
        return Optional.of(builder.build());
    }

    /**
     * S08 R-08-13 (E5-T11, E5-T14): after the N-15 of an offer are stored, each entry records that its notice reached the
     * member (a channel delivered or queued) — only while it is still `NOTIFIED` with that offer; otherwise the whole
     * delivery rolls back and the retry finds the entry changed (no N-15, no N-46).
     */
    @Override public void stored(NotificationTrigger trigger, String code, List<StoredNotification> notifications) {
        switch (code) {
            case "N-15" -> {
                for (var notification : notifications) {
                    boolean reached = notification.reachable();
                    String entryId = notification.subject().waitlistEntryId();
                    if (!reached || entryId == null) { continue; }
                    var entry = waitlist.findById(entryId).orElse(null);
                    if (entry == null || entry.state() != WaitlistState.NOTIFIED || entry.notifiedAt() == null) {
                        throw new IllegalStateException("Waiting-list entry " + entryId + " changed while its N-15 was written"); // rolls the notices back
                    }
                    if (entry.notifiedAt().equals(entry.offerNotifiedAt())) { continue; } // a redelivery: already recorded
                    if (!waitlist.markOfferNotified(entryId, entry.notifiedAt())) {
                        throw new IllegalStateException("Waiting-list entry " + entryId + " changed while its N-15 was written");
                    }
                }
            }
            case "N-19" -> notifications.stream().filter(n -> n.has("APP", "DELIVERED")).forEach(this::noticeSent);
            default -> { }
        }
    }
    /** S10 §7: an N-19 e-mail the provider accepted is a notice sent too (a member without the app). */
    @Override public void sent(StoredNotification notification, String channel) {
        if ("N-19".equals(notification.code())) { noticeSent(notification); }
    }
    private void noticeSent(StoredNotification notification) {
        String bookingId = notification.subject().bookingId();
        if (bookingId == null) { return; }
        var attendance = attendances.byBookings(List.of(bookingId)).get(bookingId);
        if (attendance != null && attendance.noShowNotice() != null && attendance.noShowNotice().eventId() != null) {
            attendances.noticeSent(attendance.id(), attendance.noShowNotice().eventId(), context.now());
        }
    }

    private Optional<NotificationFacts> seatTaken(NotificationTrigger trigger) {
        if (!context.enabled(Module.WAITLIST) || context.waitlistMode() != WaitlistMode.ALL_AT_ONCE) { return Optional.empty(); }
        String classId = "WaitlistConsolidated".equals(trigger.type())
                ? waitlist.findById(trigger.aggregateId()).map(WaitlistEntry::classSessionId).orElse(null) : trigger.text("classId");
        if (classId == null) { return Optional.empty(); }
        // E5-T11: a hold released without a booking (DELETE /seat-holds) takes no seat; only a confirmation's does.
        if ("SeatHoldReleased".equals(trigger.type()) && bookings.live(classId, Objects.toString(trigger.text("dogId"), "")).isEmpty()) { return Optional.empty(); }
        // Read-only first (E5-T08): an ordinary booking (no open offer, no taken one) stops here, without a write.
        var live = waitlist.live(classId);
        if (live.stream().noneMatch(e -> e.notifiedAt() != null)) { return Optional.empty(); }
        if (live.stream().anyMatch(e -> e.state() == WaitlistState.NOTIFIED)) {
            transactions.write(List.of(classId), () -> { locks.lock(classId); return transitions.demoteIfFull(classId, BookingActor.system()); });
            live = waitlist.live(classId);
        }
        var lost = live.stream().filter(e -> e.state() == WaitlistState.ACTIVE && e.notifiedAt() != null && e.notifiedAt().equals(e.offerNotifiedAt()))
                .sorted(Comparator.comparing(WaitlistEntry::id)).toList();
        if (lost.isEmpty()) { return Optional.empty(); }
        var session = classes.find(classId).orElse(null);
        if (session == null) { return Optional.empty(); }
        var builder = classValues(NotificationFacts.builder(), session, session.startsAt());
        for (var entry : lost) {
            builder.member(new NotificationFacts.MemberSubject(entry.memberId(), entry.dogId(), Map.of("entityId", entry.id()),
                    NotificationSubject.waitlistEntry(entry.id()).with(NotificationSubject.classSession(classId)),
                    "N-46:" + entry.id() + ":" + entry.notifiedAt().toEpochMilli()));
        }
        return Optional.of(builder.build());
    }

    private Optional<NotificationFacts> noShow(NotificationTrigger trigger) {
        var batch = attendances.byIds(trigger.ids("attendanceIds")).stream().sorted(Comparator.comparing(com.agilityhub.core.clubs.bookings.persistence.Attendance::id)).toList();
        if (batch.isEmpty()) { return Optional.empty(); }
        var builder = NotificationFacts.builder();
        var sessions = new java.util.HashMap<String, Optional<ClassSessionBookingAccess.Session>>();
        for (var attendance : batch) {
            // S10 §8 / E6-T04: «la classe de {class_date}», the class's own date in full, never «ahir»; `class_description`
            // (catalog N-19, E65) is the class's description in the recipient's language.
            var values = new java.util.LinkedHashMap<String, Object>();
            values.put("class_date", new NotificationValues.AbsoluteDate(attendance.classDate()));
            sessions.computeIfAbsent(attendance.classSessionId(), classes::find).ifPresent(session ->
                    values.put("class_description", new NotificationValues.Localized(locale -> classes.labels(session, locale).description())));
            values.put("entityId", attendance.bookingId());
            builder.member(new NotificationFacts.MemberSubject(attendance.memberId(), attendance.dogId(), values,
                    NotificationSubject.booking(attendance.bookingId()).with(NotificationSubject.classSession(attendance.classSessionId()))));
        }
        return Optional.of(builder.build());
    }

    private Optional<NotificationFacts> reminder(NotificationTrigger trigger) {
        String bookingId = trigger.text("bookingId");
        if (bookingId == null) { return Optional.empty(); }
        var booking = bookings.findById(bookingId).orElse(null);
        var session = booking == null ? null : classes.find(booking.classSessionId()).orElse(null);
        var builder = NotificationFacts.builder().value("kind", "CLASS").subject(NotificationSubject.booking(bookingId));
        if (session != null) {
            classValues(builder, session, booking.classStartsAt()).value("date", booking.classStartsAt()).value("time", booking.classStartsAt());
            builder.subject(NotificationSubject.classSession(session.id()));
        }
        String memberId = Objects.requireNonNullElse(trigger.text("memberId"), booking == null ? null : booking.memberId());
        if (memberId == null) { return Optional.empty(); }
        String dogId = Objects.requireNonNullElse(trigger.text("dogId"), booking == null ? "" : booking.dogId());
        return Optional.of(builder.member(new NotificationFacts.MemberSubject(memberId, dogId.isEmpty() ? null : dogId, Map.of(), NotificationSubject.booking(bookingId))).build());
    }

    /** `class_date`, `class_time`, `class_description` and `ring_name` of a class (formatted by the engine in each recipient's language). */
    private NotificationFacts.Builder classValues(NotificationFacts.Builder builder, ClassSessionBookingAccess.Session session, Instant startsAt) {
        return builder.value("class_date", startsAt).value("class_time", startsAt)
                .value("class_description", new NotificationValues.Localized(locale -> classes.labels(session, locale).description()))
                .value("ring_name", new NotificationValues.Localized(locale -> Objects.toString(classes.labels(session, locale).ringName(), "")))
                .subject(NotificationSubject.classSession(session.id()));
    }

    /** R-11-11 `CLAIM_SEAT.enabled`: the entry is still `NOTIFIED` and, in FIFO, `confirmBy` is after now. */
    @Override public boolean stillNotified(String waitlistEntryId, Instant now) {
        return waitlist.findById(waitlistEntryId).filter(e -> e.state() == WaitlistState.NOTIFIED && (e.confirmBy() == null || e.confirmBy().isAfter(now))).isPresent();
    }
    /** R-11-16 for a class booking: `ACTIVE` and not started yet. */
    public boolean activeAndFuture(String bookingId, Instant now) {
        return bookings.findById(bookingId).filter(b -> b.state() == BookingState.ACTIVE && b.classStartsAt() != null && b.classStartsAt().isAfter(now)).isPresent();
    }
}

package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.AttendanceEvent;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S10 §8 / CATALEG_NOTIFICACIONS N-19 «T'hem trobat a faltar», produced only by the outbox consumer of the P3 batch
 * (`notifications.N-19` ← `NoShowNoticeDue{attendanceIds[], bookingIds[]}`): one per booking of the batch, to the dog's
 * owner (the booking's `memberId`), APP + EMAIL (PERSONAL: the member's preferences exist from E7-T03; until then the
 * email always goes, as N-20's), rendered in the recipient's language with `dog_name` and `class_date` = the class's own
 * date (never «ahir»). A mark changed after the batch still gets its notice (R-10-06: «canviar-la després no retira res»).
 * Ids are `eventId:bookingId:channel`, so a redelivered event notifies once.
 *
 * <p>S10 §7 «avís ja enviat»: once a channel of the booking is SENT (the APP row is SENT on insertion; the email when the
 * provider accepts it) the consumer writes `noShowNotice.sentAt` on that attendance, once. This is the S10 §7
 * `NotificationSent{N-19}` contract folded into the dispatcher: before E7 an APP row publishes no `NotificationSent`.
 */
@Service
public class NoShowNotifications {
    static final String CODE = "N-19";
    private final AttendanceRepository attendances; private final BookingMemberAccess census; private final NotificationAccounts accounts;
    private final SystemNotificationService notifications; private final BookingContext context;
    public NoShowNotifications(AttendanceRepository attendances, BookingMemberAccess census, NotificationAccounts accounts,
            SystemNotificationService notifications, BookingContext context) {
        this.attendances = attendances; this.census = census; this.accounts = accounts; this.notifications = notifications; this.context = context;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deliver(String eventId, AttendanceEvent event) {
        if (event.kind() != AttendanceEvent.Kind.NoShowNoticeDue) { return; }
        try (var tenant = TenantContext.open(event.clubId())) {
            var ids = new ArrayList<String>();
            if (event.payload().get("attendanceIds") instanceof Collection<?> values) { values.forEach(value -> ids.add(Objects.toString(value))); }
            for (Attendance attendance : attendances.byIds(ids).stream().sorted(Comparator.comparing(Attendance::id)).toList()) { send(eventId, attendance); }
        }
    }

    private void send(String eventId, Attendance attendance) {
        var member = census.member(attendance.memberId()).orElse(null); if (member == null) { return; }
        var account = member.accountId() == null ? null : accounts.find(member.accountId()).orElse(null);
        var locale = locale(account == null || account.locale() == null ? member.locale() : account.locale());
        var values = new LinkedHashMap<String, Object>();
        values.put("club_name", context.config().club().name());
        values.put("dog_name", census.dog(attendance.dogId()).map(BookingMemberAccess.Dog::name).orElse(""));
        values.put("class_date", classDate(attendance, locale));
        values.put("entityId", attendance.bookingId());
        String key = eventId + ":" + attendance.bookingId();
        var sent = new ArrayList<String>();
        if (account != null) { notifications.appOnce(key + ":app", CODE, account.id(), values); sent.add(key + ":app"); }
        if (member.email() != null) {
            if (account != null && member.email().equalsIgnoreCase(account.email())) {
                notifications.sendOnceLocalized(key + ":email", CODE, account.id(), locale.toLanguageTag(), values);
            } else {
                notifications.sendApplicantOnce(key + ":email", CODE, member.email(), locale.toLanguageTag(), values);
            }
            sent.add(key + ":email");
        }
        if (sent.stream().anyMatch(notifications::sent)) { attendances.noticeSent(attendance.id(), eventId, context.now()); }
    }
    /** The class's own club-local date, written out in full in the recipient's language («dilluns, 12 d'octubre de 2026»). */
    static String classDate(Attendance attendance, Locale locale) {
        return attendance.classDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale));
    }
    private Locale locale(String tag) {
        return tag != null && Set.of("ca", "es", "en").contains(tag) ? Locale.forLanguageTag(tag) : Locale.forLanguageTag(context.config().club().defaultLocale());
    }

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        @Bean("notifications.N-19") DomainEventHandler<AttendanceEvent> n19(NoShowNotifications service) {
            return new DomainEventHandler<>() {
                public String eventType() { return "NoShowNoticeDue"; } public Class<AttendanceEvent> eventClass() { return AttendanceEvent.class; }
                public void handle(String id, AttendanceEvent event) { service.deliver(id, event); }
            };
        }
    }
}

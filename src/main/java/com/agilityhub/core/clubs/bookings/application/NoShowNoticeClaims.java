package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.AttendanceEvent;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * S10 R-10-06 / S15 R-15-13 contract (the P3 job itself is E6-T04): in one transaction, claim every NO_SHOW of the club
 * not yet queued whose class date is before {@code today} ({@link AttendanceRepository#claimForNoShowNotice}) and publish
 * one `NoShowNoticeDue{attendanceIds[], bookingIds[]}` for them (N-19, one per booking, is S11's). The claimed rows keep
 * the event id in `noShowNotice.eventId`. A second run claims nothing and publishes nothing.
 */
@Service
public class NoShowNoticeClaims {
    public record Claim(String eventId, List<String> attendanceIds, List<String> bookingIds) { }
    private final BookingContext context; private final BookingTransactions transactions; private final AttendanceRepository attendances;
    private final EventPublisher publisher;
    public NoShowNoticeClaims(BookingContext context, BookingTransactions transactions, AttendanceRepository attendances, EventPublisher publisher) {
        this.context = context; this.transactions = transactions; this.attendances = attendances; this.publisher = publisher;
    }
    /** @param today the club-local date of the run (`classDate < today`) */
    public Claim claim(LocalDate today) {
        return transactions.write(List.of(), () -> {
            String clubId = TenantContext.require(); var now = context.now(); String token = "claim:" + UUID.randomUUID();
            var claimed = attendances.claimForNoShowNotice(clubId, today, now, token);
            if (claimed.isEmpty()) { return new Claim(null, List.of(), List.of()); }
            var attendanceIds = claimed.stream().map(Attendance::id).toList(); var bookingIds = claimed.stream().map(Attendance::bookingId).toList();
            var payload = new LinkedHashMap<String, Object>(); payload.put("attendanceIds", attendanceIds); payload.put("bookingIds", bookingIds);
            String eventId = publisher.publish(new AttendanceEvent(AttendanceEvent.Kind.NoShowNoticeDue, clubId, clubId, now, payload, null, null, DomainEvent.Origin.SYSTEM));
            attendances.noticeEvent(token, eventId);
            return new Claim(eventId, attendanceIds, bookingIds);
        });
    }
    public LocalDate today() { return context.today(); }
}

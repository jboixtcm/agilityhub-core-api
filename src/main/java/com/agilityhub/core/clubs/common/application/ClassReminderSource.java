package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.bookings.application.ClassReminders;
import com.agilityhub.core.clubs.common.application.ports.ReminderSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** S15 R-15-14 P4 over S08 class bookings: `clubs.common` reads `clubs.bookings` (never the other way round). */
@Component
public class ClassReminderSource implements ReminderSource {
    private final ClassReminders reminders;
    public ClassReminderSource(ClassReminders reminders) { this.reminders = reminders; }

    @Override public Kind kind() { return Kind.CLASS; }
    @Override public List<Candidate> scope(Instant after, Instant until) { return reminders.scope(after, until).stream().map(ClassReminderSource::candidate).toList(); }
    @Override public Optional<Candidate> current(String id) { return reminders.current(id).map(ClassReminderSource::candidate); }
    @Override public boolean markSent(String id, Instant startsAt, Instant at) { return reminders.markSent(id, startsAt, at); }

    private static Candidate candidate(ClassReminders.Reminder r) { return new Candidate(r.bookingId(), r.memberId(), r.dogId(), r.classStartsAt(), r.bookedAt()); }
}

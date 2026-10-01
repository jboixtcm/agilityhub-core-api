package com.agilityhub.core.clubs.common.application.ports;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * S15 R-15-14 P4: one kind of booking the `reminders` process reminds of, read and marked in the owning context. The
 * process lives in `clubs.common` because it spans S08 class bookings and S09 training bookings, and `clubs.training`
 * already depends on `clubs.bookings` and `clubs.common`: the training adapter implements this port (training → common), and
 * the class one is a `clubs.common` adapter over S08's `ClassReminders` (common → bookings), so no context ever depends back.
 * All reads and writes are tenant-scoped (the current club).
 */
public interface ReminderSource {
    enum Kind { CLASS, TRAINING }

    /**
     * A booking that may need a reminder: its id, the dog's owner (`memberId`, whose `reminderMinutesBefore` applies), the dog,
     * when it starts and when it was booked.
     */
    record Candidate(String id, String memberId, String dogId, Instant startsAt, Instant bookedAt) { }

    Kind kind();
    /** ACTIVE bookings starting in `(after, until]` with no reminder yet, by start. */
    List<Candidate> scope(Instant after, Instant until);
    /** The booking as it is now, while it is still in scope (ACTIVE, no reminder yet); empty otherwise. */
    Optional<Candidate> current(String id);
    /** Sets `reminderSentAt` once, on a booking still ACTIVE, without a reminder and starting at `startsAt`; false otherwise. */
    boolean markSent(String id, Instant startsAt, Instant at);
}

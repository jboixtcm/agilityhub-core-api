package com.agilityhub.core.clubs.scheduling.application.ports;

import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.*;

/** S09 mutations join the caller's transaction and use the shared catalog reference lock. */
public interface TrainingConflictPort {
    record Booking(String id, String ringId, Instant from, Instant to, String memberName, String dogName) { }
    List<Booking> findActiveBookings(String ringId, Instant from, Instant to);
    @Transactional(propagation = Propagation.MANDATORY)
    void cancelByClub(List<String> bookingIds, String cancelReason);
    /**
     * R-09-13: `$inc` the ring-slot sequences of every training grid slot of the ring overlapping `[from, to)` inside the
     * caller's transaction, before it reads {@link #findActiveBookings}: a concurrent booking of one of those slots
     * then conflicts with the caller in Mongo instead of both committing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void lockSlots(String ringId, Instant from, Instant to);
}

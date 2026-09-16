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
}

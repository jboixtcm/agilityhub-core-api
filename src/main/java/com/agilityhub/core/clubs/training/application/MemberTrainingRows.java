package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.bookings.application.ports.MemberTrainingRowsPort;
import com.agilityhub.core.clubs.census.application.TrainingMemberAccess;
import com.agilityhub.core.clubs.training.domain.TrainingBookingState;
import com.agilityhub.core.clubs.training.persistence.TrainingBookingRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * S09 → S08 screen 03: the `TRAINING` rows of `GET /me/home` are the ACTIVE training bookings of the dogs the member
 * sees (own and family group, the same scope as `/me/training-bookings`) that have not ended yet.
 */
@Service
public class MemberTrainingRows implements MemberTrainingRowsPort {
    private final TrainingBookingRepository bookings; private final TrainingMemberAccess census; private final TrainingBookingService service;
    public MemberTrainingRows(TrainingBookingRepository bookings, TrainingMemberAccess census, TrainingBookingService service) {
        this.bookings = bookings; this.census = census; this.service = service;
    }
    @Override public List<Row> upcoming(String memberId, Collection<String> dogIds, Instant now) {
        if (dogIds.isEmpty()) { return List.of(); }
        var rings = service.rings();
        // A slot lasts training.slotMinutes (well under a day): starting after now − 1 day covers every row still running.
        return bookings.visible(census.reachableMembers(memberId), dogIds, TrainingBookingState.ACTIVE, now.minusSeconds(86_400), null).stream()
                .filter(b -> dogIds.contains(b.dogId()) && b.endsAt().isAfter(now))
                .map(b -> {
                    var ring = rings.get(b.ringId());
                    return new Row(b.id(), b.dogId(), b.startsAt(), b.endsAt(), ring == null ? null : ring.name(), ring == null ? null : ring.color());
                }).toList();
    }
}

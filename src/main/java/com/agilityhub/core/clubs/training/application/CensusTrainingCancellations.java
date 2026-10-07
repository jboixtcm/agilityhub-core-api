package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.census.application.ports.*;
import com.agilityhub.core.platform.application.Module;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CensusTrainingCancellations implements TrainingCancellationPort {
    private final TrainingBookingService service; private final TrainingContext context;
    public CensusTrainingCancellations(TrainingBookingService service, TrainingContext context) {
        this.service = service; this.context = context;
    }
    @Override public List<LifecycleCancellation> inside(String memberId, LocalDate from, LocalDate to, boolean cancel, boolean leave) {
        if (!context.enabled(Module.FREE_TRAINING)) { return List.of(); }
        var inside = service.futureOwnedBy(memberId, context.now()).stream().filter(b -> {
            var date = b.startsAt().atZone(context.zone()).toLocalDate();
            return !date.isBefore(from) && (to == null || !date.isAfter(to));
        }).toList();
        if (cancel) { service.cancelForLifecycle(inside, leave); }
        return inside.stream().map(b -> new LifecycleCancellation("TRAINING", b.id(), b.startsAt().atZone(context.zone()).toLocalDate())).toList();
    }
}

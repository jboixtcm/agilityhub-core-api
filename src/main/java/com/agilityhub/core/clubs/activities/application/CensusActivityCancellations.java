package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.census.application.ports.*;
import com.agilityhub.core.clubs.activities.domain.RegistrationState;
import com.agilityhub.core.platform.application.Module;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CensusActivityCancellations implements ActivityCancellationPort {
    private final ActivityRegistrationService service;
    public CensusActivityCancellations(ActivityRegistrationService service) { this.service = service; }
    @Override public List<LifecycleCancellation> inside(String memberId, LocalDate from, LocalDate to, boolean cancel, boolean leave) {
        if (!service.context.enabled(Module.ACTIVITIES)) { return List.of(); }
        var result = service.registrations.forMember(memberId).stream().filter(r -> r.state() != RegistrationState.CANCELLED)
                .map(r -> new LifecycleCancellation("ACTIVITY", r.id(), service.activities.require(r.activityId()).date()))
                .filter(r -> !r.sessionDate().isBefore(from) && (to == null || !r.sessionDate().isAfter(to))).toList();
        if (cancel) {
            if (leave) { service.cancelForMemberLeft(memberId, from.atStartOfDay(ZoneId.of(service.context.config().club().timeZone())).toInstant().minusNanos(1)); }
            else { service.cancelForInactivity(memberId, from, to); }
        }
        return result;
    }
}

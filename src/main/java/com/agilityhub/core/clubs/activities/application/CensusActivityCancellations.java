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
        var now = service.context.clock.instant();
        var result = new ArrayList<LifecycleCancellation>();
        for (var r : service.registrations.forMember(memberId)) {
            if (r.state() == RegistrationState.CANCELLED) { continue; }
            var activity = service.activities.require(r.activityId());
            // Like classes and trainings, only activities still to come: a period that starts at once never touches the past.
            if (!service.context.times(activity).startsAt().isAfter(now)) { continue; }
            if (activity.date().isBefore(from) || to != null && activity.date().isAfter(to)) { continue; }
            result.add(new LifecycleCancellation("ACTIVITY", r.id(), activity.date()));
        }
        if (cancel) {
            if (leave) { service.cancelForMemberLeft(memberId, from.atStartOfDay(ZoneId.of(service.context.config().club().timeZone())).toInstant().minusNanos(1)); }
            else { service.cancelForInactivity(memberId, from, to); }
        }
        return List.copyOf(result);
    }
}

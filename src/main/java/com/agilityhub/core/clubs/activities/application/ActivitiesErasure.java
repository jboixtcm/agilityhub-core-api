package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.platform.application.privacy.ErasureBlockReason;
import com.agilityhub.core.platform.application.privacy.ErasureCheck;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.clubs.activities.persistence.ActivityErasureRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * S14 WP-14-D, S07 (a collection added after S14 was written): an `ACTIVE` or `WAITLISTED` registration to an activity still to
 * start is a future booking (R-14-14 `FUTURE_BOOKINGS`); R-14-15 keeps the registrations and drops the member's own name as registrant.
 */
@Component
public class ActivitiesErasure implements ErasureCheck, ErasureStep {
    private final ActivityErasureRepository erasure;
    public ActivitiesErasure(ActivityErasureRepository erasure) { this.erasure = erasure; }

    @Override public Set<ErasureBlockReason> blockReasons(Collection<String> memberIds, Instant now) {
        return erasure.futureBookings(memberIds, now) ? EnumSet.of(ErasureBlockReason.FUTURE_BOOKINGS) : EnumSet.noneOf(ErasureBlockReason.class);
    }
    @Override public ErasureEffect erase(ErasureSubject subject) {
        var effect = ErasureEffect.builder();
        erasure.erase(subject.memberIds(), subject.accountIds()).forEach(effect::count);
        return effect.build();
    }
}

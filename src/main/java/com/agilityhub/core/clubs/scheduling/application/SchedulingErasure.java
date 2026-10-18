package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.persistence.SchedulingErasureRepository;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import org.springframework.stereotype.Component;

/**
 * S14 R-14-15, S06/S10 (E11-T01 round 2): the attendance summary of a class saved by an erased member's account
 * (`attendanceSummary.savedByAccountId`) names «Abonat suprimit #87» as `savedByName`. A summary saved before the account was
 * stored has no account to match and keeps its name.
 */
@Component
public class SchedulingErasure implements ErasureStep {
    private final SchedulingErasureRepository erasure;
    public SchedulingErasure(SchedulingErasureRepository erasure) { this.erasure = erasure; }

    @Override public ErasureEffect erase(ErasureSubject subject) {
        long changed = 0;
        for (var member : subject.members()) {
            if (member.accountId() != null) { changed += erasure.renameSavedBy(member.accountId(), subject.memberLabel(member.id())); }
        }
        return ErasureEffect.builder().count("class_sessions", changed).build();
    }
}

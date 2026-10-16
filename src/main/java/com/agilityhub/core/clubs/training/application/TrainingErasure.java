package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.platform.application.privacy.ErasureBlockReason;
import com.agilityhub.core.platform.application.privacy.ErasureCheck;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.platform.application.privacy.ErasureMember;
import com.agilityhub.core.platform.application.privacy.MemberDataPackage;
import com.agilityhub.core.platform.application.privacy.MemberDataSource;
import com.agilityhub.core.clubs.training.persistence.TrainingErasureRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** S14 WP-14-D, S09: an `ACTIVE` training booking still to start is a `FUTURE_BOOKINGS` (R-14-14); R-14-15 drops its `cancelNote`. */
@Component
public class TrainingErasure implements ErasureCheck, ErasureStep, MemberDataSource {
    private final TrainingErasureRepository erasure;
    public TrainingErasure(TrainingErasureRepository erasure) { this.erasure = erasure; }

    @Override public Set<ErasureBlockReason> blockReasons(Collection<String> memberIds, Instant now) {
        return erasure.futureBookings(memberIds, now) ? EnumSet.of(ErasureBlockReason.FUTURE_BOOKINGS) : EnumSet.noneOf(ErasureBlockReason.class);
    }
    @Override public ErasureEffect erase(ErasureSubject subject) {
        var effect = ErasureEffect.builder();
        erasure.erase(subject.memberIds(), subject.accountIds()).forEach(effect::count);
        return effect.build();
    }

    /** S14 R-14-13: `trainingBookings[]`. */
    @Override public void contribute(ErasureMember member, MemberDataPackage data) {
        data.section("trainingBookings", erasure.rows(member.id()).stream().map(MemberDataPackage::row).toList());
    }
}

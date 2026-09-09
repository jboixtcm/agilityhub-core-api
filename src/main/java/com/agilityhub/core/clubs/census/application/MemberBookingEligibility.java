package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** S07/S08/S09 call this for the actor and dog owner, including family and impersonation bookings. */
@Service
public class MemberBookingEligibility {
    private final CensusAccess access;
    public MemberBookingEligibility(CensusAccess access) { this.access = access; }
    public void check(String actorMemberId, String dogId) {
        var actor = access.mutableMember(actorMemberId); var dog = access.dogs.require(dogId); var owner = access.mutableMember(dog.memberId);
        if (!actor.id.equals(owner.id)) {
            var group = actor.familyGroupId == null ? null : access.groups.findById(actor.familyGroupId).orElse(null);
            if (!access.enabled(Module.FAMILY_GROUP) || group == null || !"ACTIVE".equals(group.status)
                    || !group.memberIds.contains(actor.id) || !group.memberIds.contains(owner.id)) { throw new ApiException(ErrorCode.FORBIDDEN); }
        }
        blocked(actor); blocked(owner);
        if (!"ACTIVE".equals(actor.status) || !"ACTIVE".equals(owner.status)) { throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE); }
        if (!"ACTIVE".equals(dog.status)) { throw new ApiException(ErrorCode.DOG_NOT_ACTIVE); }
    }
    private void blocked(Member member) {
        var block = map(member.bookingBlock);
        if (Boolean.TRUE.equals(block.get("active"))) { throw new ApiException(ErrorCode.BOOKING_BLOCKED, select(block, "reason", "since")); }
    }
}

package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

@Service
public class DogTransferService {
    private final CensusAccess access; private final DogService dogs; private final CensusEvents events; private final ClubClock clock;
    public DogTransferService(CensusAccess access, DogService dogs, CensusEvents events, ClubClock clock) { this.access = access; this.dogs = dogs; this.events = events; this.clock = clock; }
    public String owner(String id) { return access.dogs.require(id).memberId; }
    @Transactional
    @Audited(action = AuditAction.DOG_TRANSFERRED, entityType = "'Dog'", entity = "#id", reason = "#reason", member = "owner(#id)")
    public void transfer(String id, String toMemberId, String reason) {
        var dog = access.mutableDog(id); var target = access.mutableMember(toMemberId);
        if (dog.memberId.equals(toMemberId)) { throw new ApiException(ErrorCode.SAME_MEMBER); }
        if (!"ACTIVE".equals(target.status)) { throw new ApiException(ErrorCode.TARGET_MEMBER_NOT_ACTIVE); }
        dogs.noBookings(dog);
        if (access.enabled(Module.PACKS) && !access.references.pack(id, clock.today(TenantContext.require())).isEmpty()) { throw new ApiException(ErrorCode.DOG_HAS_OPEN_PACK); }
        String from = dog.memberId; dog.memberId = target.id; access.dogs.save(dog); dogs.clearSelections(id);
        events.emit("DogTransferred", "Dog", id, object("dogId", id, "memberId", target.id, "fromMemberId", from, "toMemberId", target.id));
    }
}

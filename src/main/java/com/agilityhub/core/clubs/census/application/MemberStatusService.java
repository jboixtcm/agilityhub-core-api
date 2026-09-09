package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.CensusRules;
import com.agilityhub.core.identity.application.CensusIdentityService;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.events.MemberStatusChanged;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** S04/S13/S15 invoke effective transitions; scheduled leave remains a dated ACTIVE member. */
@Service
public class MemberStatusService {
    private final CensusAccess access; private final EventPublisher events; private final CensusIdentityService identities;
    private final Clock clock; private final ClubClock clubClock; private final AuditActorProvider actors;
    public MemberStatusService(CensusAccess access, EventPublisher events, CensusIdentityService identities, Clock clock, ClubClock clubClock, AuditActorProvider actors) {
        this.access = access; this.events = events; this.identities = identities; this.clock = clock; this.clubClock = clubClock; this.actors = actors;
    }
    @Transactional
    @Audited(action = AuditAction.MEMBER_STATUS_CHANGED, entityType = "'Member'", entity = "#id", member = "#id", reason = "#reason")
    public void transition(String id, String after, LocalDate effectiveDate, String reason) {
        var member = access.mutableMember(id); CensusRules.transition(member.status, after, reason);
        LocalDate today = clubClock.today(TenantContext.require());
        if (effectiveDate == null || effectiveDate.isAfter(today)) { throw new ApiException(ErrorCode.INVALID_STATE); }
        String before = member.status;
        if ("ACTIVE".equals(after)) {
            if (member.memberNumber == null || member.accountId == null) { throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE); }
            identities.reactivate(id, member.accountId); member.leaveDate = null; member.leaveRequestId = null;
            if (member.joinedAt == null) { member.joinedAt = clock.instant(); }
        } else { member.leaveDate = effectiveDate; }
        member.status = after; access.members.save(member);
        var actor = actors.current(); var user = CurrentUser.current();
        events.publish(new MemberStatusChanged(TenantContext.require(), id, clock.instant(),
                object("memberId", id, "before", before, "after", after, "effectiveDate", effectiveDate.toString()),
                actor.accountId(), actor.impersonatedMemberId(), user == null ? DomainEvent.Origin.SYSTEM : user.origin()));
    }
}

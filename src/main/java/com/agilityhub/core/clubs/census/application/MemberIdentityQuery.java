package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.clubs.census.domain.CensusRules;
import com.agilityhub.core.shared.domain.*;
import org.springframework.stereotype.Service;

@Service
public class MemberIdentityQuery implements com.agilityhub.core.shared.application.MemberIdentityAccess, com.agilityhub.core.shared.application.TeamMemberAccess {
    private final CensusRepository<Member> members;
    public MemberIdentityQuery(CensusRepository<Member> members) { this.members = members; }
    @Override public TeamMember teamMember(String memberId) {
        var member = members.require(memberId); CensusRules.mutable(member.erasedAt);
        return new TeamMember(member.id, member.accountId, member.status, member.firstName);
    }
    @Override public String impersonationAccount(String memberId) {
        var member = members.require(memberId); CensusRules.mutable(member.erasedAt);
        if (!("ACTIVE".equals(member.status) || "INACTIVE".equals(member.status)) || member.accountId == null) {
            throw new ApiException(ErrorCode.IMPERSONATION_DENIED);
        }
        return member.accountId;
    }
    @Override public Bootstrap bootstrap(String memberId) {
        if (memberId == null) { return new Bootstrap(null, null, null); }
        return members.findById(memberId).map(member -> new Bootstrap(member.gender, member.lastDogForClass, member.lastDogForTraining))
                .orElse(new Bootstrap(null, null, null));
    }
}

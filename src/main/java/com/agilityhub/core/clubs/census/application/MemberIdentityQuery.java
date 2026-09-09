package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.MemberIdentityRepository;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class MemberIdentityQuery implements com.agilityhub.core.shared.application.MemberIdentityAccess, com.agilityhub.core.shared.application.TeamMemberAccess {
    private final MemberIdentityRepository members;
    public MemberIdentityQuery(MemberIdentityRepository members) { this.members = members; }
    @Override public TeamMember teamMember(String memberId) {
        var member = members.findById(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return new TeamMember(member.id(), member.accountId(), member.status(), member.firstName());
    }
    @Override public String impersonationAccount(String memberId) {
        var member = members.findById(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!("ACTIVE".equals(member.status()) || "INACTIVE".equals(member.status())) || member.accountId() == null) {
            throw new ApiException(ErrorCode.IMPERSONATION_DENIED);
        }
        return member.accountId();
    }
}

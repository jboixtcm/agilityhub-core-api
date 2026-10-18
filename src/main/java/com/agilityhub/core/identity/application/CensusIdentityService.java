package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.platform.application.CensusClubSettings;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CensusIdentityService {
    private final AccountRepository accounts; private final MembershipRepository memberships;
    private final MagicLinkService links; private final RateLimits limits; private final CensusClubSettings clubs;
    private final AccountMembershipRepository clubsOfAccount;
    public CensusIdentityService(AccountRepository accounts, MembershipRepository memberships, MagicLinkService links, RateLimits limits, CensusClubSettings clubs,
            AccountMembershipRepository clubsOfAccount) {
        this.accounts = accounts; this.memberships = memberships; this.links = links; this.limits = limits; this.clubs = clubs;
        this.clubsOfAccount = clubsOfAccount;
    }
    /**
     * S01 R-01-14 / T-01-23 (INC-37): an administrator's edit of a member's name renames the member's account (`Account.name`) when
     * that membership is the account's only one; an account that also belongs to another club keeps its name (the member's name
     * is the club's, the account's is the person's own). Inside the member edit's transaction.
     */
    public void syncName(String accountId, String name) {
        if (accountId == null || name == null || name.isBlank()
                || memberships.findByAccountId(accountId).filter(own -> own.status() != Membership.Status.ERASED).isEmpty()) { return; }
        if (clubsOfAccount.countNotErased(accountId) != 1) { return; }
        accounts.findById(accountId).filter(account -> account.status() == Account.Status.ACTIVE && !name.strip().equals(account.name()))
                .ifPresent(account -> accounts.patch(accountId, null, name.strip()));
    }
    public String displayName(String accountId) {
        if(accountId==null || memberships.findByAccountId(accountId).isEmpty()) return "";
        return accounts.findById(accountId).map(Account::name).orElse("");
    }
    public String accessEmail(String accountId) {
        var account = accounts.findById(accountId).orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_ACTIVE));
        if (account.status() != Account.Status.ACTIVE || memberships.findByAccountId(accountId)
                .filter(item -> item.status() == Membership.Status.ACTIVE).isEmpty()) { throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE); }
        return account.email();
    }
    public void limitResend(String email, String ip) {
        long retry = Math.max(limits.retryAfter(RateLimits.Route.MAGIC_LINK_EMAIL, TokenService.digest(email)),
                limits.retryAfter(RateLimits.Route.MAGIC_LINK_IP, ip == null ? "census" : ip));
        if (retry > 0) { throw new ApiException(ErrorCode.RATE_LIMITED, Map.of("retryAfter", retry)); }
    }
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void sendAccess(String eventId, String accountId) {
        links.createAndSend(accessEmail(accountId), MagicLinkToken.Purpose.ACCESS_RESEND, "clubs-app", null, clubs.appHost(), null, null, eventId);
    }
    public void reactivate(String memberId, String accountId) {
        if (accountId == null) { throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE); }
        var member = memberships.findByAccountId(accountId).filter(item -> memberId.equals(item.memberId()))
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_ACTIVE));
        if (member.status() == Membership.Status.ERASED) { throw new ApiException(ErrorCode.MEMBER_ERASED); }
        if (member.status() != Membership.Status.ACTIVE) { var roles = new java.util.HashSet<>(member.roles()); roles.add(com.agilityhub.core.identity.domain.Role.MEMBER);
            memberships.saveTeam(new Membership(member.id(), member.accountId(), member.clubId(), member.memberId(), roles,
                    Membership.Status.ACTIVE, com.agilityhub.core.identity.domain.Role.MEMBER, false, member.instructorId(),
                    member.createdAt(), member.lastAccessAt(), member.adminProfile(), member.version() + 1, member.updatedAt(),
                    member.createdByAccountId(), member.updatedByAccountId())); }
    }
}

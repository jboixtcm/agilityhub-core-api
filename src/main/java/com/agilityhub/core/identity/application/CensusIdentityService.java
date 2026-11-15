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
    /**
     * AUTH-02 (S03 R-03-06b): the login e-mail of the member's account as D10 shows it, with its suppression mark (`BOUNCED`,
     * `COMPLAINED`, S11 R-11-08) so that staff see why mail does not arrive; null without an account. AUTH-03: and when the
     * account's last access or recovery e-mail finally failed (`accessEmailFailedAt`, null otherwise).
     */
    public Map<String, Object> login(String accountId) {
        if (accountId == null) { return null; }
        return accounts.findById(accountId).map(account -> {
            var login = new java.util.LinkedHashMap<String, Object>();
            login.put("email", account.email());
            login.put("emailStatus", account.emailStatus() == null ? null : account.emailStatus().name());
            login.put("accessEmailFailedAt", accounts.accessEmailFailedAt(accountId));
            return (Map<String, Object>) login;
        }).orElse(null);
    }
    /**
     * AUTH-02 (S03 R-03-06b): the checks of an ADMIN's request to change a member's login e-mail, before any link is made. The
     * account must be active with an active membership here (`422 MEMBER_NOT_ACTIVE`); a club changes only an account that is
     * its own alone, as R-01-14 renames only such an account: not one with another club's membership or a platform role
     * (`403 FORBIDDEN`); the new address must differ from the current one (`400 VALIDATION_ERROR`, `email` · `UNCHANGED`); and the
     * magic-link limits of `/auth/magic-link` apply to the new address (10/h per address, 60/h per IP). Returns the normalised new address.
     */
    public String checkLoginEmailChange(String accountId, String email, String ip) {
        String current = accessEmail(accountId);
        var account = accounts.findById(accountId).orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_ACTIVE));
        if (!account.platformRoles().isEmpty() || clubsOfAccount.countNotErased(accountId) != 1) { throw new ApiException(ErrorCode.FORBIDDEN); }
        String normalized = com.agilityhub.core.identity.domain.Email.normalize(email);
        if (normalized.equals(current)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", java.util.List.of(Map.of("field", "email", "code", "UNCHANGED"))));
        }
        limitResend(normalized, ip);
        return normalized;
    }
    /** AUTH-02: mails the confirmation link of a checked change to the new address, after the request's transaction. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void sendLoginEmailChange(String accountId, String email, String requestedByAccountId) {
        links.sendEmailChange(accountId, email, requestedByAccountId, clubs.appHost());
    }
    public void limitResend(String email, String ip) {
        long retry = Math.max(limits.retryAfter(RateLimits.Route.MAGIC_LINK_EMAIL, TokenService.digest(email)),
                limits.retryAfter(RateLimits.Route.MAGIC_LINK_IP, ip == null ? "census" : ip));
        if (retry > 0) { throw new ApiException(ErrorCode.RATE_LIMITED, Map.of("retryAfter", retry)); }
    }
    /**
     * WEB-01 (owner decision 13-11-2026, S03 R-03-06): the D10 welcome e-mail is limited per address (the magic link's 10/h) and per
     * club (`ACCESS_RESEND_CLUB`), never by the administrator's IP: that bucket stays the members' own sign-in requests', which
     * come from the same club network.
     */
    public void limitWelcome(String email) {
        long retry = Math.max(limits.retryAfter(RateLimits.Route.MAGIC_LINK_EMAIL, TokenService.digest(email)),
                limits.retryAfter(RateLimits.Route.ACCESS_RESEND_CLUB, TenantContext.require()));
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

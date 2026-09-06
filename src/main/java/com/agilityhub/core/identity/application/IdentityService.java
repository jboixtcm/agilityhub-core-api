package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.TenantContext;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
    private final AccountRepository accounts;
    private final MembershipRepository memberships;
    private final PasswordHasher passwords;
    private final SecurityEvents events;
    public IdentityService(AccountRepository accounts, MembershipRepository memberships, PasswordHasher passwords, SecurityEvents events) {
        this.accounts = accounts; this.memberships = memberships; this.passwords = passwords; this.events = events;
    }
    public Session authenticate(String email, String password) {
        Account account = accounts.findByEmail(email).orElse(null);
        boolean matches = passwords.verify(password, account == null ? null : account.passwordHash());
        if (!matches) {
            events.record(SecurityEvents.Type.LOGIN_FAILED, account == null ? null : account.id(), TenantContext.current());
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return session(account);
    }
    public Session current(String accountId) {
        return session(accounts.findById(accountId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED)));
    }
    private Session session(Account account) {
        if (account.status() != Account.Status.ACTIVE) { throw new ApiException(ErrorCode.ACCOUNT_BLOCKED); }
        Membership membership = memberships.findByAccountId(account.id()).orElseThrow(() -> new ApiException(ErrorCode.NO_MEMBERSHIP));
        if (membership.status() != Membership.Status.ACTIVE) { throw new ApiException(ErrorCode.MEMBERSHIP_SUSPENDED); }
        return new Session(account, membership);
    }
    public record Session(Account account, Membership membership) { }
}

package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.LoginLockout;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
    private final AccountRepository accounts;
    private final MembershipRepository memberships;
    private final PasswordHasher passwords;
    private final SecurityEvents events;
    private final IdentityTransactions transactions;
    private final AuthSettings settings;
    private final Clock clock;
    public IdentityService(AccountRepository accounts, MembershipRepository memberships, PasswordHasher passwords, SecurityEvents events,
                           IdentityTransactions transactions, AuthSettings settings, Clock clock) {
        this.accounts = accounts; this.memberships = memberships; this.passwords = passwords; this.events = events;
        this.transactions = transactions; this.settings = settings; this.clock = clock;
    }
    public Session authenticate(String email, String password) {
        // Return an error outcome so failure counters commit before the HTTP error is thrown.
        var outcome = transactions.run(() -> {
            Account account = accounts.findByEmail(email).orElse(null);
            boolean matches = passwords.verify(password, account == null ? null : account.passwordHash());
            if (account != null && account.status() == Account.Status.ACTIVE && account.lockout().locked(clock.instant())) {
                return new Outcome(null, account.id(), ErrorCode.LOGIN_LOCKED, account.lockout().retryAfter(clock.instant()), false);
            }
            if (!matches) {
                if (account != null && account.status() == Account.Status.ACTIVE) {
                    var lock = account.lockout().failed(clock.instant(), settings.integer("auth.lockoutMaxAttempts"), settings.integer("auth.lockoutMinutes"));
                    accounts.lockout(account.id(), lock);
                    if (lock.locked(clock.instant())) { return new Outcome(null, account.id(), ErrorCode.LOGIN_LOCKED, lock.retryAfter(clock.instant()), true); }
                }
                return new Outcome(null, account == null ? null : account.id(), ErrorCode.INVALID_CREDENTIALS, 0, false);
            }
            var session = session(account);
            accounts.lockout(account.id(), LoginLockout.empty());
            return new Outcome(session, account.id(), null, 0, false);
        });
        if (outcome.error() != null) {
            events.record(SecurityEvents.Type.LOGIN_FAILED, outcome.accountId(), TenantContext.current());
            if (outcome.newLock()) { events.record(SecurityEvents.Type.LOGIN_LOCKED, outcome.accountId(), TenantContext.current()); }
            throw new ApiException(outcome.error(), outcome.retryAfter() > 0 ? Map.of("retryAfter", outcome.retryAfter()) : Map.of());
        }
        return outcome.session();
    }
    public Session current(String accountId) {
        return session(accounts.findById(accountId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED)));
    }
    private Session session(Account account) {
        if (account.status() != Account.Status.ACTIVE) { throw new ApiException(ErrorCode.ACCOUNT_BLOCKED); }
        if (TenantContext.current() == null) { return new Session(account, null); }
        Membership membership = memberships.findByAccountId(account.id()).orElseThrow(() -> new ApiException(ErrorCode.NO_MEMBERSHIP));
        if (membership.status() != Membership.Status.ACTIVE) { throw new ApiException(ErrorCode.MEMBERSHIP_SUSPENDED); }
        return new Session(account, membership);
    }
    public record Session(Account account, Membership membership) { }
    private record Outcome(Session session, String accountId, ErrorCode error, long retryAfter, boolean newLock) { }
}

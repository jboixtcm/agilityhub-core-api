package com.agilityhub.core.identity.application;

import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.AccountSessionRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordService {
    private final IdentityService identities;
    private final AccountRepository accounts;
    private final AccountSessionRepository sessions;
    private final TokenService tokens;
    private final PasswordHasher passwords;
    private final CompromisedPasswords compromised;
    private final AuthSettings settings;
    private final IdentityTransactions transactions;
    private final EventPublisher events;
    private final SystemNotificationService notifications;
    private final Clock clock;
    public PasswordService(IdentityService identities, AccountRepository accounts, AccountSessionRepository sessions, TokenService tokens,
            PasswordHasher passwords, CompromisedPasswords compromised, AuthSettings settings, IdentityTransactions transactions,
            EventPublisher events, SystemNotificationService notifications, Clock clock) {
        this.identities = identities; this.accounts = accounts; this.sessions = sessions; this.tokens = tokens;
        this.passwords = passwords; this.compromised = compromised; this.settings = settings; this.transactions = transactions;
        this.events = events; this.notifications = notifications; this.clock = clock;
    }
    @Transactional(propagation = Propagation.NEVER)
    public void change(String accountId, String clientId, String familyId, String current, String next, String repeat) {
        var account = identities.current(accountId).account();
        if (account.passwordHash() != null && (current == null || !passwords.verify(current, account.passwordHash()))) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!Objects.equals(next, repeat)) { throw new ApiException(ErrorCode.PASSWORD_MISMATCH); }
        if (next == null || next.codePointCount(0, next.length()) < settings.integer("auth.passwordMinLength")) { throw new ApiException(ErrorCode.PASSWORD_TOO_SHORT); }
        if (next.strip().equalsIgnoreCase(account.email())) { throw new ApiException(ErrorCode.PASSWORD_COMPROMISED); }
        if (settings.enabled("auth.checkCompromisedPasswords") && compromised.contains(next)) { throw new ApiException(ErrorCode.PASSWORD_COMPROMISED); }
        String hash = passwords.hash(next);
        transactions.run(() -> {
            var fresh = identities.current(accountId).account();
            if (!Objects.equals(fresh.passwordHash(), account.passwordHash())) { throw new ApiException(ErrorCode.INVALID_CREDENTIALS); }
            tokens.requireFamily(accountId, clientId, familyId, fresh.familyVersion());
            accounts.password(accountId, hash, clock.instant());
            sessions.revokeOthers(accountId, familyId, clock.instant());
            sessions.preserveFamily(accountId, familyId, fresh.familyVersion() + 1);
            events.publish(new IdentityEvent(IdentityEvent.Kind.PasswordChanged, null, accountId, clock.instant(), Map.of("accountId", accountId)));
            return null;
        });
        notifications.send("N-26", accountId, Map.of("changed_at", clock.instant().toString()));
    }
}

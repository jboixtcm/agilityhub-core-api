package com.agilityhub.core.identity.application;

import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountMembershipRepository;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.AccountSessionRepository;
import com.agilityhub.core.identity.persistence.MagicLinkTokenRepository;
import com.agilityhub.core.identity.persistence.RefreshToken;
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
    /**
     * AUTH-01 (S01 R-01-05, owner decision of 13 Nov 2026): the minimum length of a new password for a staff account, one that holds
     * `ADMIN` or `INSTRUCTOR` in a club or the platform role `AGILITYHUB_ADMIN`; a member keeps `auth.passwordMinLength`. A floor that
     * no parameter lowers; a higher club minimum still applies.
     */
    public static final int STAFF_PASSWORD_MIN_LENGTH = 12;
    private final IdentityService identities;
    private final AccountRepository accounts;
    private final AccountSessionRepository sessions;
    private final MagicLinkTokenRepository magicLinks;
    private final TokenService tokens;
    private final PasswordHasher passwords;
    private final CompromisedPasswords compromised;
    private final AuthSettings settings;
    private final IdentityTransactions transactions;
    private final EventPublisher events;
    private final SystemNotificationService notifications;
    private final AccountMembershipRepository staffRoles;
    private final Clock clock;
    public PasswordService(IdentityService identities, AccountRepository accounts, AccountSessionRepository sessions,
            MagicLinkTokenRepository magicLinks, TokenService tokens, PasswordHasher passwords, CompromisedPasswords compromised,
            AuthSettings settings, IdentityTransactions transactions, EventPublisher events, SystemNotificationService notifications,
            AccountMembershipRepository staffRoles, Clock clock) {
        this.identities = identities; this.accounts = accounts; this.sessions = sessions; this.magicLinks = magicLinks; this.tokens = tokens;
        this.passwords = passwords; this.compromised = compromised; this.settings = settings; this.transactions = transactions;
        this.events = events; this.notifications = notifications; this.staffRoles = staffRoles; this.clock = clock;
    }
    /**
     * R-01-05: `current` is required whenever a password exists, except once in a session opened by a RESET magic link within its
     * 15 minutes (E5-T27, ruling E49). A failed attempt keeps that mark; the first change clears it, so the ordinary rule applies
     * again afterwards. The change also ends the account's unused magic links (AUTH-06): the other sessions are revoked, and so are
     * the links that would open new ones.
     */
    @Transactional(propagation = Propagation.NEVER)
    public void change(String accountId, String clientId, String familyId, String current, String next, String repeat) {
        var account = identities.current(accountId).account();
        boolean reset = account.passwordHash() != null && (current == null || current.isEmpty());
        if (reset ? !sessions.passwordResetOpen(accountId, familyId, clock.instant())
                : account.passwordHash() != null && !passwords.verify(current, account.passwordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!Objects.equals(next, repeat)) { throw new ApiException(ErrorCode.PASSWORD_MISMATCH); }
        int minimum = minimumLength(account);
        if (next == null || next.codePointCount(0, next.length()) < minimum) { throw new ApiException(ErrorCode.PASSWORD_TOO_SHORT, Map.of("minLength", minimum)); }
        if (next.strip().equalsIgnoreCase(account.email())) { throw new ApiException(ErrorCode.PASSWORD_COMPROMISED); }
        if (settings.enabled("auth.checkCompromisedPasswords") && compromised.contains(next)) { throw new ApiException(ErrorCode.PASSWORD_COMPROMISED); }
        String hash = passwords.hash(next);
        transactions.run(() -> {
            var fresh = identities.current(accountId).account();
            if (!Objects.equals(fresh.passwordHash(), account.passwordHash())) { throw new ApiException(ErrorCode.INVALID_CREDENTIALS); }
            tokens.requireFamily(accountId, clientId, familyId, fresh.familyVersion());
            if (reset && !sessions.consumePasswordReset(accountId, familyId, clock.instant())) { throw new ApiException(ErrorCode.INVALID_CREDENTIALS); }
            sessions.clearPasswordReset(accountId, familyId);
            accounts.password(accountId, hash, clock.instant());
            sessions.revokeOthers(accountId, familyId, clock.instant());
            magicLinks.revokeUnused(accountId, clock.instant());
            sessions.preserveFamily(accountId, familyId, fresh.familyVersion() + 1);
            events.publish(IdentityEvents.of(IdentityEvent.Kind.PasswordChanged, null, accountId, clock.instant(), Map.of("accountId", accountId)));
            return null;
        });
        // INC-37 (S01 §8, N-26 {changed_at, device}): the device of the session that made the change, as `/me/sessions` names it.
        notifications.send("N-26", accountId, Map.of("changed_at", clock.instant().toString(), "device", device(accountId, familyId)));
    }
    /** AUTH-01 (S01 R-01-05): `auth.passwordMinLength`, and at least {@link #STAFF_PASSWORD_MIN_LENGTH} for a staff account. */
    int minimumLength(Account account) {
        int minimum = settings.integer("auth.passwordMinLength");
        boolean staff = account.platformRoles().contains(Account.PlatformRole.AGILITYHUB_ADMIN) || staffRoles.holdsStaffRole(account.id());
        return staff ? Math.max(minimum, STAFF_PASSWORD_MIN_LENGTH) : minimum;
    }
    private String device(String accountId, String familyId) {
        return tokens.sessions(accountId).stream().filter(session -> session.familyId().equals(familyId)).map(RefreshToken::deviceLabel)
                .filter(Objects::nonNull).findFirst().orElse(TokenService.device(null));
    }
}

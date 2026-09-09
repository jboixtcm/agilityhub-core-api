package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Email;
import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccountService implements com.agilityhub.core.shared.application.AccountAccess {
    private final AccountRepository accounts;
    private final IdentityTransactions transactions;
    private final EventPublisher events;
    private final Clock clock;
    public AccountService(AccountRepository accounts, IdentityTransactions transactions, EventPublisher events, Clock clock) {
        this.accounts = accounts; this.transactions = transactions; this.events = events; this.clock = clock;
    }
    public Account getOrCreate(String email, String name, String locale, Account.Source source) {
        return getOrCreate(email, name, locale, source, null, null);
    }
    /** Seed initialization is insert-only, including under concurrent account creation. */
    public Account getOrCreate(String email, String name, String locale, Account.Source source,
                               String initialPasswordHash, Boolean initialOnboardingPending) {
        String normalized = Email.normalize(email);
        validate(locale, name);
        if (source == null || name == null || locale == null) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        return transactions.run(() -> {
            var existing = accounts.findByEmail(normalized);
            if (existing.isPresent()) { return existing.get(); }
            var candidate = new Account(UUID.randomUUID().toString(), normalized, name.strip(), locale, initialPasswordHash, Set.of(), Account.Status.ACTIVE,
                    new Account.Security(0, null, initialPasswordHash == null ? null : clock.instant(), 0), Map.of(),
                    initialOnboardingPending == null ? source == Account.Source.IMPORT_LEARN || source == Account.Source.MIGRATION : initialOnboardingPending,
                    clock.instant(), null, null, source, null, null);
            boolean created = accounts.createIfAbsent(candidate);
            var account = accounts.findByEmail(normalized).orElseThrow();
            if (created) { events.publish(new IdentityEvent(IdentityEvent.Kind.AccountCreated, null, account.id(), clock.instant(),
                    Map.of("accountId", account.id(), "emailHash", TokenService.digest(normalized), "source", source.name()))); }
            return account;
        });
    }
    public void patch(String accountId, String locale, String name) {
        validate(locale, name);
        transactions.run(() -> {
            var account = active(accountId);
            accounts.patch(accountId, locale, name == null ? null : name.strip());
            if (locale != null && !locale.equals(account.locale())) {
                events.publish(new IdentityEvent(IdentityEvent.Kind.AccountLocaleChanged, null, accountId, clock.instant(),
                        Map.of("accountId", accountId, "locale", locale)));
            }
            return null;
        });
    }
    public Account active(String id) {
        var account = accounts.findById(id).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (account.status() != Account.Status.ACTIVE) { throw new ApiException(ErrorCode.ACCOUNT_BLOCKED); }
        return account;
    }
    @Override public boolean blocked(String accountId, java.time.Instant issuedAt) {
        return accounts.findById(accountId).map(account -> account.status() != Account.Status.ACTIVE
                || (account.security() != null && account.security().accessRevokedAt() != null
                && (issuedAt == null || !issuedAt.isAfter(account.security().accessRevokedAt())))).orElse(false);
    }
    private void validate(String locale, String name) {
        if (locale != null && !Set.of("ca", "es", "en").contains(locale)) { throw new ApiException(ErrorCode.LOCALE_NOT_SUPPORTED); }
        if (name != null && (name.isBlank() || name.length() > 200)) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
    }
}

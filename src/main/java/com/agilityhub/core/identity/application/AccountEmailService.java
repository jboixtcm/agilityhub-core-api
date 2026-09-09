package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditableLoader;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.shared.application.NotificationAccounts;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountEmailService implements NotificationAccounts, AuditableLoader {
    private final AccountRepository accounts;
    public AccountEmailService(AccountRepository accounts) { this.accounts = accounts; }
    @Override public Optional<Recipient> find(String accountId) {
        return accounts.findById(accountId).map(account -> new Recipient(account.id(), account.email(), account.locale(), account.emailStatus()));
    }
    @Override public String entityType() { return "Account"; }
    @Override public Object load(String accountId) {
        return accounts.findById(accountId).map(account -> {
            var snapshot = new java.util.LinkedHashMap<String, Object>();
            snapshot.put("platformRoles", account.platformRoles().stream().map(Enum::name).sorted().toList());
            snapshot.put("emailStatus", account.emailStatus());
            snapshot.put("name", account.name());
            snapshot.put("locale", account.locale());
            snapshot.put("onboardingPending", account.onboardingPending());
            snapshot.put("consents", account.consents().stream().filter(consent ->
                    consent.policy() == com.agilityhub.core.identity.persistence.Account.ConsentPolicy.PLATFORM
                            || java.util.Objects.equals(consent.clubId(), com.agilityhub.core.shared.application.TenantContext.current())).map(consent -> {
                var value = new java.util.LinkedHashMap<String, Object>();
                value.put("policy", consent.policy()); value.put("clubId", consent.clubId());
                value.put("version", consent.version()); value.put("acceptedAt", consent.acceptedAt());
                return value;
            }).toList());
            return snapshot;
        }).orElse(null);
    }
    @Override @Transactional
    @Audited(action = AuditAction.ACCOUNT_EMAIL_STATUS_CHANGED, entityType = "'Account'", entity = "#accountId")
    public void markEmailStatus(String accountId, String expectedEmail, EmailStatus status) {
        accounts.markEmailStatus(accountId, expectedEmail, status);
    }
}

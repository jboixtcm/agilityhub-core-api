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
    @Override public Object load(String accountId) { return find(accountId).orElse(null); }
    @Override @Transactional
    @Audited(action = AuditAction.ACCOUNT_EMAIL_STATUS_CHANGED, entityType = "'Account'", entity = "#accountId")
    public void markEmailStatus(String accountId, String expectedEmail, EmailStatus status) {
        accounts.markEmailStatus(accountId, expectedEmail, status);
    }
}

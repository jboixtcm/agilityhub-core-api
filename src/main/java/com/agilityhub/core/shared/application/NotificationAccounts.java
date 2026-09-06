package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.audit.AuditField;
import java.util.Optional;

/** Identity-owned port keeps notification delivery independent of identity persistence. */
public interface NotificationAccounts {
    enum EmailStatus { BOUNCED, COMPLAINED }
    record Recipient(String id, String email, String locale, @AuditField EmailStatus emailStatus) { }
    Optional<Recipient> find(String accountId);
    void markEmailStatus(String accountId, String expectedEmail, EmailStatus status);
}

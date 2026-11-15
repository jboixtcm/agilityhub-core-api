package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.audit.AuditField;
import java.util.Optional;

/** Identity-owned port keeps notification delivery independent of identity persistence. */
public interface NotificationAccounts {
    enum EmailStatus { BOUNCED, COMPLAINED }
    record Recipient(String id, String email, String locale, @AuditField EmailStatus emailStatus) { }
    Optional<Recipient> find(String accountId);
    void markEmailStatus(String accountId, String expectedEmail, EmailStatus status);
    /**
     * AUTH-03 (S01 R-01-04, S03 R-03-06b): the mark staff see on the member's record when the account's last access or recovery
     * e-mail (N-25, N-27) finally failed; {@code failedAt} null clears it once a later one is accepted. Only while
     * {@code expectedEmail} is still the account's address.
     */
    void markAccessEmailFailure(String accountId, String expectedEmail, java.time.Instant failedAt);
}

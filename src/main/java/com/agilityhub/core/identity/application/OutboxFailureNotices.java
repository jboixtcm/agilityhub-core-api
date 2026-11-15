package com.agilityhub.core.identity.application;

import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.PlatformConsoleQueries;
import com.agilityhub.core.shared.application.OutboxFailureAlerts;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OUT-01 (owner decision of 13 Nov 2026), CATALEG_NOTIFICACIONS N-57: an outbox record that ran out of its retries →
 * «Esdeveniment intern no processat» to every active platform admin by e-mail (SYSTEM: product copy, the account's language,
 * no club branding, as N-43). At most one e-mail per admin, event type and UTC day (each recipient's row id derives from the
 * three), so a consumer that fails on every event of a type does not spend the provider's daily allowance on alerts; the
 * count of failed records stays the `outbox.failed` gauge. The variables are the event's type, its club's name (the platform's
 * for a record without a club) and the persisted error type, never the event's payload.
 */
@Component
public class OutboxFailureNotices implements OutboxFailureAlerts {
    static final String CODE = "N-57";
    private static final Logger LOG = LoggerFactory.getLogger(OutboxFailureNotices.class);
    private final SystemNotificationService notifications; private final AccountRepository accounts; private final PlatformConsoleQueries clubs;
    public OutboxFailureNotices(SystemNotificationService notifications, AccountRepository accounts, PlatformConsoleQueries clubs) {
        this.notifications = notifications; this.accounts = accounts; this.clubs = clubs;
    }

    @Override public void failed(FailedEvent event) {
        var variables = Map.of("event_type", event.type(), "club_name", clubName(event.clubId()), "reason", event.error() == null ? "—" : event.error());
        String day = event.failedAt().atOffset(ZoneOffset.UTC).toLocalDate().toString();
        for (String accountId : accounts.activePlatformAdminIds()) {
            String id = UUID.nameUUIDFromBytes((CODE + ":" + event.type() + ":" + day + ":" + accountId).getBytes(StandardCharsets.UTF_8)).toString();
            try { notifications.sendOnce(id, CODE, accountId, variables); }
            catch (RuntimeException unsent) {
                // One admin's failure (an account gone meanwhile) does not keep the others from their alert.
                LOG.error("N-57 not sent accountId={} type={} error={}", accountId, event.type(), unsent.getClass().getSimpleName());
            }
        }
    }

    private String clubName(String clubId) {
        if (clubId == null) { return "AgilityHub"; }
        try { return clubs.name(clubId); }
        catch (RuntimeException unknown) { return clubId; }
    }
}

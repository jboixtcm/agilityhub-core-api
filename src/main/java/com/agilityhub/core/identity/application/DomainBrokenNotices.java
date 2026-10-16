package com.agilityhub.core.identity.application;

import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.PlatformConsoleQueries;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S17 §8 / CATALEG_NOTIFICACIONS N-43 (E10-T03): `ClubDomainBroken` → «Domini del club trencat» to every active platform admin by
 * e-mail (SYSTEM: product copy, the account's language, no club branding: it is the platform's notice, so no tenant is open).
 * The outbox delivers at least once: each recipient's row id derives from the event and the account, so a redelivery sends
 * nothing twice.
 */
@Component("identityDomainBrokenNotices")
public class DomainBrokenNotices implements DomainEventHandler<DomainBrokenNotices.ForeignEvent> {
    /** The read-only envelope of `ClubDomainBroken{clubId, host}`. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForeignEvent(String clubId, Map<String, Object> payload) { }
    static final String CODE = "N-43";
    private final SystemNotificationService notifications; private final AccountRepository accounts; private final PlatformConsoleQueries clubs;
    public DomainBrokenNotices(SystemNotificationService notifications, AccountRepository accounts, PlatformConsoleQueries clubs) {
        this.notifications = notifications; this.accounts = accounts; this.clubs = clubs;
    }
    @Override public String eventType() { return "ClubDomainBroken"; }
    @Override public Class<ForeignEvent> eventClass() { return ForeignEvent.class; }
    @Override @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void handle(String eventId, ForeignEvent event) {
        if (!(event.payload().get("host") instanceof String host)) { return; }
        var variables = Map.of("club_name", clubs.name(event.clubId()), "host", host);
        for (String accountId : accounts.activePlatformAdminIds()) {
            String id = UUID.nameUUIDFromBytes((eventId + ":" + CODE + ":" + accountId).getBytes(StandardCharsets.UTF_8)).toString();
            notifications.sendOnce(id, CODE, accountId, variables);
        }
    }
}

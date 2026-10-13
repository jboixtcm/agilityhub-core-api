package com.agilityhub.core.identity.application;

import com.agilityhub.core.shared.domain.events.ClubConfigChanged;

import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.*;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Existing ClubUpdated.diff carries an invitation intent; the outbox retries N-53 with its stable delivery id. */
@Component
public class ConsoleInvitationConsumer implements DomainEventHandler<ClubConfigChanged> {
    private final MagicLinkService links;private final PlatformConsoleQueries clubs;private final AccountRepository accounts;
    public ConsoleInvitationConsumer(MagicLinkService links,PlatformConsoleQueries clubs,AccountRepository accounts) {this.links=links;this.clubs=clubs;this.accounts=accounts;}
    public String eventType() {return "ClubUpdated";}
    public Class<ClubConfigChanged> eventClass() {return ClubConfigChanged.class;}
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void handle(String eventId,ClubConfigChanged event) {
        if(!(event.payload().get("diff") instanceof Map<?,?> diff)||!(diff.get("adminInvitation") instanceof String accountId)) return;
        try(var scope=TenantContext.open(event.clubId())) {
            String inviter=event.actorAccountId()==null?"AgilityHub":accounts.findById(event.actorAccountId()).map(a->a.name()).orElse("AgilityHub");
            links.sendAdminInvitation(eventId,accountId,clubs.get(event.clubId()).name(),inviter);
        }
    }
}

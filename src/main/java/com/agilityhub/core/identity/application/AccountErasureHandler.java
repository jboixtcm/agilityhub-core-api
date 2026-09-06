package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.shared.application.DomainEventHandler;
import org.springframework.stereotype.Component;

/** The outbox dispatcher records this consumer's event receipt in the same transaction as revocation. */
@Component("identityAccountErasure")
public class AccountErasureHandler implements DomainEventHandler<IdentityEvent> {
    private final TokenService tokens;
    public AccountErasureHandler(TokenService tokens) { this.tokens = tokens; }
    @Override public String eventType() { return "AccountErasureRequested"; }
    @Override public Class<IdentityEvent> eventClass() { return IdentityEvent.class; }
    @Override public void handle(String eventId, IdentityEvent event) {
        if (event.payload().get("accountId") instanceof String id) { tokens.revokeAll(id); }
    }
}

package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.shared.application.DomainEventHandler;
import org.springframework.stereotype.Component;

/**
 * S01 §7 `AccountErasureRequested` → revokes the account's sessions (T-01-16). The outbox dispatcher records this consumer's event
 * receipt in the same transaction as revocation. S14's member-scoped request (`scope = MEMBER`, E11-T01) concerns one club the
 * member has already left (its membership is suspended with its sessions, `MemberStatusChanged{LEFT}`), so the account's other
 * clubs keep theirs; an account's request (`scope = ACCOUNT`, or none) revokes them all.
 */
@Component("identityAccountErasure")
public class AccountErasureHandler implements DomainEventHandler<IdentityEvent> {
    private final TokenService tokens;
    public AccountErasureHandler(TokenService tokens) { this.tokens = tokens; }
    @Override public String eventType() { return "AccountErasureRequested"; }
    @Override public Class<IdentityEvent> eventClass() { return IdentityEvent.class; }
    @Override public void handle(String eventId, IdentityEvent event) {
        if ("MEMBER".equals(event.payload().get("scope"))) { return; }
        if (event.payload().get("accountId") instanceof String id) { tokens.revokeAll(id); }
    }
}

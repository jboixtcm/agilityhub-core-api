package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.shared.application.CurrentUser;
import java.time.Instant;
import java.util.Map;

/**
 * CATALEG_ESDEVENIMENTS: every event carries `actorAccountId` (+ `impersonatedMemberId`) and `origin` (E3-T10). The
 * identity events take them from the request: under impersonation the actor is the administrator, never the member.
 * Without a request user (a job, an anonymous flow) the event is the system's.
 */
final class IdentityEvents {
    private IdentityEvents() { }
    static IdentityEvent of(IdentityEvent.Kind kind, String clubId, String aggregateId, Instant occurredAt, Map<String, Object> payload) {
        var user = CurrentUser.current();
        if (user == null) { return new IdentityEvent(kind, clubId, aggregateId, occurredAt, payload); }
        var impersonation = user.impersonation();
        return new IdentityEvent(kind, clubId, aggregateId, occurredAt, payload,
                impersonation != null ? impersonation.actorAccountId() : user.accountId(),
                impersonation == null ? null : impersonation.memberId(), user.origin());
    }
}

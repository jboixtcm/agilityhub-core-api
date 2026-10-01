package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * S12 §7 events of the monthly cycle (E8-T02), published through the outbox in the writing transaction, with the request's
 * actor (the impersonator's account under impersonation) and origin (`BACKOFFICE` for the admin's D6 actions).
 */
@Component
public class BillingEvents {
    private final EventPublisher publisher; private final Clock clock;
    public BillingEvents(EventPublisher publisher, Clock clock) { this.publisher = publisher; this.clock = clock; }

    public void publish(BillingEvent.Kind kind, String aggregateId, Map<String, Object> payload) {
        var user = CurrentUser.current();
        String actor = user == null ? null : user.impersonation() != null ? user.impersonation().actorAccountId() : user.accountId();
        String impersonated = user == null || user.impersonation() == null ? null : user.impersonation().memberId();
        var origin = user == null || user.origin() == null ? DomainEvent.Origin.BACKOFFICE : user.origin();
        publisher.publish(new BillingEvent(kind, TenantContext.require(), aggregateId, clock.instant(), payload, actor, impersonated, origin));
    }
    /** The account the documents record as their author (`createdByAccountId`, `byAccountId`). */
    public static String actor() {
        var user = CurrentUser.current();
        return user == null ? null : user.impersonation() != null ? user.impersonation().actorAccountId() : user.accountId();
    }
}

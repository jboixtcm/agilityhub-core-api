package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT re-run survivor of {@link BillingEvents} (S12 §7): an event keeps the caller's origin, and only a caller without
 * one (the admin's D6 actions) publishes it as `BACKOFFICE`. Fictional accounts only.
 */
class BillingEventsSurvivors2Test {
    static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");

    final EventPublisher publisher = mock(EventPublisher.class);
    final BillingEvents events = new BillingEvents(publisher, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void E11_T06_anEventKeepsTheCallersOriginAndOnlyAMissingOneIsBackoffice() {
        try (var tenant = TenantContext.open("club-a")) {
            try (var user = CurrentUser.open(new CurrentUser("account-member", "Laura Serra", null, DomainEvent.Origin.APP))) {
                events.publish(BillingEvent.Kind.InvoicePaid, "invoice-1", Map.of("invoiceId", "invoice-1"));
            }
            try (var user = CurrentUser.open(new CurrentUser("account-admin", "Eva Roca", null, null))) {
                events.publish(BillingEvent.Kind.InvoicePaid, "invoice-2", Map.of("invoiceId", "invoice-2"));
            }
        }

        var published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(publisher, times(2)).publish(published.capture());
        assertThat(published.getAllValues()).extracting(DomainEvent::origin).containsExactly(DomainEvent.Origin.APP, DomainEvent.Origin.BACKOFFICE);
    }
}

package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.payments.domain.StripeEventOutcome;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S12 §3 received Stripe event (R-12-21). `eventId` is unique over the whole collection (Stripe's ids are global): a second
 * delivery of the same event is stored once and has no effect. Only a hash of the payload is kept, never the payload (it can
 * carry the customer's e-mail).
 */
@Document("stripe_events")
public record StripeEvent(@Id String id, String clubId, String eventId, String type, Instant receivedAt, Instant processedAt,
        StripeEventOutcome outcome, String payloadHash) implements TenantEntity { }

package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link Collection#publishedReference()} (S12 §3): Stripe's own reference is published as is, and an
 * attempt with nothing to publish has no reference (null, not an empty string).
 */
class CollectionSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");

    @Test void E11_T06_theStoredProviderReferenceIsPublishedAsIs() {
        assertThat(attempt(CollectionProvider.STRIPE, "pi_fake_1").publishedReference()).isEqualTo("pi_fake_1");
    }

    @Test void E11_T06_anAttemptWithoutAnyReferencePublishesNone() {
        assertThat(attempt(CollectionProvider.STRIPE, null).publishedReference()).isNull();
        // A SEPA attempt without mandate and a manual one without channel fall through to the same null.
        assertThat(attempt(CollectionProvider.SEPA_XML, null).publishedReference()).isNull();
        assertThat(attempt(CollectionProvider.MANUAL, null).publishedReference()).isNull();
    }

    static Collection attempt(CollectionProvider provider, String providerRef) {
        return new Collection("collection-1", "club-a", "invoice-1", provider, new Money(9000, "EUR"), CollectionStatus.SUCCEEDED,
                providerRef, null, 1, null, null, List.of(), NOW, NOW);
    }
}

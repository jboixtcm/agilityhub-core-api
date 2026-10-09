package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** E11-T06 PIT survivor of {@link StripeInbox#failed} (S12 R-12-21, T-12-15): a first failure below the maximum is retried, not exhausted. */
class StripeInboxSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final StripeInbox inbox = new StripeInbox(mongo, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach void openTenant() { TenantContext.clear(); TenantContext.open("club-a"); }
    @AfterEach void closeTenant() { TenantContext.clear(); }

    @Test void T_12_15_aFailedEventBelowTheMaximumIsRetried() {
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Document.class), eq("stripe_events")))
                .thenReturn(new Document("_id", "event-1").append("attempts", 1));

        assertThat(inbox.failed("event-1", NOW, 5)).isFalse();
    }
}

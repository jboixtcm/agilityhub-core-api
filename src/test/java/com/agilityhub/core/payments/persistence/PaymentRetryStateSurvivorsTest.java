package com.agilityhub.core.payments.persistence;

import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PaymentRetryState#failed}: a checkpoint already processed (or gone) is no failure to count,
 * a failure below the maximum is not exhausted, and the next attempt backs off exponentially (10 s doubled per attempt).
 */
class PaymentRetryStateSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);

    @Test void E11_T06_aProcessedOrMissingCheckpointIsNotExhausted() {
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Document.class), eq("stripe_events")))
                .thenReturn(null);

        assertThat(PaymentRetryState.failed(mongo, query(), "stripe_events", NOW, 5)).isFalse();
        verify(mongo, never()).updateFirst(any(Query.class), any(UpdateDefinition.class), anyString());
    }

    @Test void E11_T06_theSecondFailureWaitsTwentySecondsAndIsNotExhausted() {
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Document.class), eq("stripe_events")))
                .thenReturn(new Document("_id", "event-1").append("attempts", 2));

        assertThat(PaymentRetryState.failed(mongo, query(), "stripe_events", NOW, 5)).isFalse();

        var update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(), eq("stripe_events"));
        // 10 s << (2 − 1) = 20 s.
        assertThat(update.getValue().getUpdateObject().get("$set", Document.class).get("nextAttemptAt")).isEqualTo(NOW.plusSeconds(20));
    }

    static Query query() { return Query.query(Criteria.where("clubId").is("club-a").and("_id").is("event-1")); }
}

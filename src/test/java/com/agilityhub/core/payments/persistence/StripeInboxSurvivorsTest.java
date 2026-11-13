package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivor of {@link StripeInbox#failed} (S12 R-12-21, T-12-15): a first failure below the maximum is retried, not
 * exhausted. PAY-02 (owner decision of 13 Nov 2026): the n-th failure waits the n-th step of the backoff it is given, the one
 * after the last step stops the event and keeps its recovery data; an outage postpones without spending an attempt; a
 * redelivery re-queues only a FAILED event of the open club.
 */
class StripeInboxSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofHours(8));

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final StripeInbox inbox = new StripeInbox(mongo, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach void openTenant() { TenantContext.clear(); TenantContext.open("club-a"); }
    @AfterEach void closeTenant() { TenantContext.clear(); }

    private void attempts(int attempts) {
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(Document.class), eq("stripe_events")))
                .thenReturn(new Document("_id", "event-1").append("attempts", attempts));
    }
    private Document secondUpdate() {
        var update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo).updateFirst(any(Query.class), update.capture(), eq("stripe_events"));
        return update.getValue().getUpdateObject();
    }

    @Test void T_12_15_aFailedEventBelowTheMaximumIsRetried() {
        attempts(1);

        assertThat(inbox.failed("event-1", NOW, BACKOFF)).isFalse();
    }

    @Test void PAY_02_theNthFailureWaitsTheNthStepOfTheBackoff() {
        attempts(2);

        assertThat(inbox.failed("event-1", NOW, BACKOFF)).isFalse();

        var spent = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo).findAndModify(any(Query.class), spent.capture(), any(FindAndModifyOptions.class), eq(Document.class), eq("stripe_events"));
        assertThat(spent.getValue().getUpdateObject()).isEqualTo(new Document("$inc", new Document("attempts", 1)).append("$set", new Document("outcome", "FAILED")));
        assertThat(secondUpdate()).isEqualTo(new Document("$set", new Document("nextAttemptAt", NOW.plus(Duration.ofMinutes(5)))));
    }

    @Test void PAY_02_theFailureAfterTheLastStepStopsTheEventAndKeepsItsRecoveryData() {
        attempts(BACKOFF.size() + 1);

        assertThat(inbox.failed("event-1", NOW, BACKOFF)).isTrue();

        // processedAt ends the retries; the stored `work` stays until the retention cleanup (no $unset of it).
        assertThat(secondUpdate()).isEqualTo(new Document("$set", new Document("processedAt", NOW)).append("$unset", new Document("nextAttemptAt", 1)));
    }

    @Test void PAY_02_aFailureOfAnEventAlreadyProcessedChangesNothing() {
        assertThat(inbox.failed("event-1", NOW, BACKOFF)).isFalse();
        verify(mongo, never()).updateFirst(any(Query.class), any(UpdateDefinition.class), anyString());
    }

    @Test void PAY_02_anOutagePostponesTheEventWithoutSpendingAnAttempt() {
        inbox.postpone("event-1", NOW.plus(Duration.ofMinutes(5)));

        var query = ArgumentCaptor.forClass(Query.class);
        var update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo).updateFirst(query.capture(), update.capture(), eq("stripe_events"));
        assertThat(query.getValue().getQueryObject()).containsEntry("clubId", "club-a").containsEntry("_id", "event-1").containsEntry("processedAt", null);
        assertThat(update.getValue().getUpdateObject())
                .isEqualTo(new Document("$set", new Document("outcome", "FAILED").append("nextAttemptAt", NOW.plus(Duration.ofMinutes(5)))));
    }

    @Test void PAY_02_aRedeliveryRequeuesOnlyAFailedEventOfTheOpenClubWithTheDeliveredRecoveryData() {
        var work = new Document("created", 1790236800L).append("object", new Document("id", "cs_fake_1"));
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq("stripe_events")))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null), UpdateResult.acknowledged(0, 0L, null));

        assertThat(inbox.requeue("event-1", work)).isTrue();
        assertThat(inbox.requeue("event-1", work)).isFalse();

        var query = ArgumentCaptor.forClass(Query.class);
        var update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(mongo, times(2)).updateFirst(query.capture(), update.capture(), eq("stripe_events"));
        assertThat(query.getValue().getQueryObject()).containsEntry("clubId", "club-a").containsEntry("_id", "event-1").containsEntry("outcome", "FAILED");
        assertThat(update.getValue().getUpdateObject()).isEqualTo(new Update().set("work", work).unset("outcome").unset("attempts").unset("processedAt")
                .unset("nextAttemptAt").getUpdateObject());
    }
}

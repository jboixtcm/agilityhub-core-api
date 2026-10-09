package com.agilityhub.core.identity.persistence;

import com.mongodb.client.result.DeleteResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link OidcStateRepository} (S01 R-01-11, T-01-13): an authorization flow or code is consumed
 * once; a second use (or an expired one) deletes nothing and is false.
 */
class OidcStateRepositorySurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final OidcStateRepository repository = new OidcStateRepository(mongo);

    @Test void T_01_13_aReusedFlowIsNotConsumedAgain() {
        when(mongo.remove(any(Query.class), eq(OidcState.Flow.class))).thenReturn(DeleteResult.acknowledged(0));

        assertThat(repository.consumeFlow("flow-hash", "browser-hash", NOW)).isFalse();
    }

    @Test void T_01_13_aLiveFlowIsConsumedOnce() {
        when(mongo.remove(any(Query.class), eq(OidcState.Flow.class))).thenReturn(DeleteResult.acknowledged(1));

        assertThat(repository.consumeFlow("flow-hash", "browser-hash", NOW)).isTrue();
    }

    @Test void T_01_13_aReusedCodeIsNotConsumedAgain() {
        when(mongo.remove(any(Query.class), eq(OidcState.Code.class))).thenReturn(DeleteResult.acknowledged(0));

        assertThat(repository.consumeCode("code-hash", NOW)).isFalse();
    }

    @Test void T_01_13_aLiveCodeIsConsumedOnce() {
        when(mongo.remove(any(Query.class), eq(OidcState.Code.class))).thenReturn(DeleteResult.acknowledged(1));

        assertThat(repository.consumeCode("code-hash", NOW)).isTrue();
    }
}

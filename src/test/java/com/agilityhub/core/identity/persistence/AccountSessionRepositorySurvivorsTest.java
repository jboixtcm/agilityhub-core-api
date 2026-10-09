package com.agilityhub.core.identity.persistence;

import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link AccountSessionRepository} (S01 R-01-05, T-01-09; ruling E49): a session without a live
 * RESET mark (a LOGIN link, an expired mark, or a concurrent change that took it first) neither reports it open nor consumes it.
 */
class AccountSessionRepositorySurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final AccountSessionRepository repository = new AccountSessionRepository(mongo);

    @Test void T_01_09_aSessionWithoutALiveResetMarkIsNotOpen() {
        when(mongo.exists(any(Query.class), eq(RefreshToken.class))).thenReturn(false);

        assertThat(repository.passwordResetOpen("acc-1", "family-1", NOW)).isFalse();
    }

    @Test void T_01_09_aSessionWithALiveResetMarkIsOpen() {
        when(mongo.exists(any(Query.class), eq(RefreshToken.class))).thenReturn(true);

        assertThat(repository.passwordResetOpen("acc-1", "family-1", NOW)).isTrue();
    }

    @Test void T_01_09_aConcurrentSecondChangeFindsNoMarkToConsume() {
        when(mongo.updateMulti(any(Query.class), any(UpdateDefinition.class), eq(RefreshToken.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThat(repository.consumePasswordReset("acc-1", "family-1", NOW)).isFalse();
    }

    @Test void T_01_09_theFirstChangeConsumesTheLiveMark() {
        when(mongo.updateMulti(any(Query.class), any(UpdateDefinition.class), eq(RefreshToken.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertThat(repository.consumePasswordReset("acc-1", "family-1", NOW)).isTrue();
    }
}

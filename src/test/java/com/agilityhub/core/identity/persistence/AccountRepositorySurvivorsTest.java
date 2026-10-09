package com.agilityhub.core.identity.persistence;

import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivor of {@link AccountRepository#createIfAbsent} (S01 R-01-01, T-01-01: `getOrCreate` is idempotent):
 * the upsert creates only when Mongo reports an upserted id; an existing email is not a creation.
 */
class AccountRepositorySurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final AccountRepository repository = new AccountRepository(mongo);

    /** The candidate AccountService.getOrCreate (:37-40) upserts: ACTIVE, with its `Security` record and source. */
    static Account account() {
        return new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, NOW, null, null, Account.Source.CONSOLE, null, null);
    }

    @Test void T_01_01_anExistingEmailIsNotCreatedAgain() {
        when(mongo.upsert(any(Query.class), any(UpdateDefinition.class), eq(Account.class))).thenReturn(UpdateResult.acknowledged(1, 0L, null));

        assertThat(repository.createIfAbsent(account())).isFalse();
    }

    @Test void T_01_01_aNewEmailIsCreated() {
        when(mongo.upsert(any(Query.class), any(UpdateDefinition.class), eq(Account.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, new BsonString("acc-1")));

        assertThat(repository.createIfAbsent(account())).isTrue();
    }
}

package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link MagicLinkTokenRepository} (S01 R-01-04, T-01-03, T-01-08): `insert` returns the stored
 * link in both the club and the global (ID) context, a second exchange of a link finds it used: `consume` is false, and
 * `trim` revokes nothing while only three links are live.
 */
class MagicLinkTokenRepositorySurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final MagicLinkTokenRepository repository = new MagicLinkTokenRepository(mongo);

    @BeforeEach void reset() { TenantContext.clear(); }
    @AfterEach void clear() { TenantContext.clear(); }

    /** As MagicLinkService (:95-97) writes it: `userAgent` is the TokenService.device summary. */
    static MagicLinkToken link(String clubId, String clientId) {
        return new MagicLinkToken("ml-1", "hash-1", "acc-1", clubId, clientId, MagicLinkToken.Purpose.LOGIN, null,
                NOW, NOW.plusSeconds(900), null, "ip-hash", "Firefox / macOS");
    }

    @Test void T_01_03_aGlobalLinkInsertReturnsTheStoredLink() {
        var link = link(null, "id-web");
        when(mongo.insert(link)).thenReturn(link);

        assertThat(repository.insert(link)).isSameAs(link);
    }

    @Test void T_01_03_aClubLinkInsertReturnsTheStoredLink() {
        TenantContext.open(CLUB);
        var link = link(CLUB, "clubs-app");
        when(mongo.insert(link)).thenReturn(link);

        assertThat(repository.insert(link)).isSameAs(link);
    }

    @Test void T_01_08_aSecondExchangeFindsTheLinkUsed() {
        TenantContext.open(CLUB);
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(MagicLinkToken.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThat(repository.consume("ml-1", NOW)).isFalse();
    }

    @Test void T_01_08_theFirstExchangeConsumesTheLink() {
        TenantContext.open(CLUB);
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(MagicLinkToken.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertThat(repository.consume("ml-1", NOW)).isTrue();
    }

    /**
     * T-01-03 (R-01-04: at most 3 live links per account and purpose). `auth.magicLinkMinutes` accepts 0
     * (parameters/catalog.yaml, `constraints: {"min": 0}`), so after the platform lowers it to 0 the newest LOGIN link expires
     * at its own creation instant (MagicLinkService.java:93-99 with the injected clock) and is not among the live links that
     * `trim` reads (`expiresAt > now`). The three earlier links, requested at 15 minutes, are exactly the limit: none is revoked.
     */
    @Test void T_01_03_threeEarlierLiveLinksAreTheLimitWhenTheNewestExpiredAtCreation() {
        TenantContext.open(CLUB);
        when(mongo.find(any(Query.class), eq(MagicLinkToken.class))).thenReturn(java.util.List.of(
                login("ml-3", NOW.minusSeconds(60)), login("ml-2", NOW.minusSeconds(120)), login("ml-1", NOW.minusSeconds(180))));

        repository.trim("acc-1", MagicLinkToken.Purpose.LOGIN, NOW, "ml-4");

        verify(mongo, never()).updateMulti(any(Query.class), any(UpdateDefinition.class), eq(MagicLinkToken.class));
    }

    /**
     * AUTH-06 (S01 R-01-05): a password change ends the account's live unused links in every club and of every purpose, in one write
     * that marks them used; spent and expired links are left as they are.
     */
    @Test void AUTH_06_revokeUnusedMarksEveryLiveUnusedLinkOfTheAccountUsed() {
        TenantContext.open(CLUB);
        var query = org.mockito.ArgumentCaptor.forClass(Query.class);
        var update = org.mockito.ArgumentCaptor.forClass(UpdateDefinition.class);

        repository.revokeUnused("acc-1", NOW);

        verify(mongo).updateMulti(query.capture(), update.capture(), eq(MagicLinkToken.class));
        var filter = query.getValue().getQueryObject();
        assertThat(filter.keySet()).containsExactlyInAnyOrder("accountId", "usedAt", "expiresAt");
        assertThat(filter.get("accountId")).isEqualTo("acc-1");
        assertThat(filter.get("usedAt")).isNull();
        assertThat(filter.get("expiresAt", org.bson.Document.class)).isEqualTo(new org.bson.Document("$gt", NOW));
        assertThat(update.getValue().getUpdateObject()).isEqualTo(new org.bson.Document("$set", new org.bson.Document("usedAt", NOW)));
    }

    /** A club LOGIN link requested at `createdAt` under the default `auth.magicLinkMinutes = 15`. */
    static MagicLinkToken login(String id, Instant createdAt) {
        return new MagicLinkToken(id, "hash-" + id, "acc-1", CLUB, "clubs-app", MagicLinkToken.Purpose.LOGIN, null,
                createdAt, createdAt.plusSeconds(900), null, "ip-hash", "Firefox / macOS");
    }
}

package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.identity.domain.Role;
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
 * E11-T06 PIT survivors of {@link RefreshTokenRepository} (S01 R-01-06, T-01-04): `insert` returns the stored session like
 * every {@code TenantRepository}, and a rotation that matched no live row (rotated, revoked or expired meanwhile) is false.
 */
class RefreshTokenRepositorySurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final RefreshTokenRepository repository = new RefreshTokenRepository(mongo);

    @BeforeEach void reset() { TenantContext.clear(); }
    @AfterEach void clear() { TenantContext.clear(); }

    /**
     * As TokenService.issue (:204-210) writes it: a club session has the membership's active profile, a global (ID host) one has
     * none; the device is the TokenService.device summary.
     */
    static RefreshToken token(String clubId, String clientId) {
        return new RefreshToken("rt-1", "hash-1", "acc-1", clubId, clientId, "family-1", 0, NOW, NOW.plusSeconds(86_400), NOW, null, null,
                clubId == null ? null : Role.MEMBER, "Firefox / Linux", RefreshToken.Status.ACTIVE);
    }

    @Test void T_01_04_aClubSessionInsertReturnsTheStoredToken() {
        TenantContext.open(CLUB);
        var token = token(CLUB, "clubs-app");
        when(mongo.insert(token)).thenReturn(token);

        assertThat(repository.insert(token)).isSameAs(token);
    }

    @Test void T_01_04_aGlobalSessionInsertReturnsTheStoredToken() {
        var token = token(null, "id-web");
        when(mongo.insert(token)).thenReturn(token);

        assertThat(repository.insert(token)).isSameAs(token);
    }

    @Test void T_01_04_aRotationThatMatchedNoLiveRowIsFalse() {
        TenantContext.open(CLUB);
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(RefreshToken.class))).thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThat(repository.rotate("rt-1", "hash-2", NOW)).isFalse();
    }

    @Test void T_01_04_aRotationThatMovedTheRowIsTrue() {
        TenantContext.open(CLUB);
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(RefreshToken.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertThat(repository.rotate("rt-1", "hash-2", NOW)).isTrue();
    }
}

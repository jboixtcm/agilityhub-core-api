package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.SigningKeyRepository;
import com.agilityhub.core.identity.persistence.SigningKeyRing;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link SigningKeys} (ADR-010; S01 T-01-13): without `OIDC_MASTER_KEY` a local/test process signs with
 * an ephemeral ring under a random master key, and with a master key the first process initialises the shared ring. Collaborators
 * are mocks; the master key is a fictional test value.
 */
class SigningKeysSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    final SigningKeyRepository repository = mock(SigningKeyRepository.class);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    static MockEnvironment profiles(String... active) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    @Test void T_01_13_aTestProcessWithoutMasterKeySignsWithAnEphemeralRingOfTwoKeys() {
        var keys = new SigningKeys(repository, clock, profiles("test"), "");

        assertThat(keys.persistent()).isFalse();
        assertThat(keys.keys()).hasSize(2);
        assertThat(keys.current().isPrivate()).isTrue();
        verifyNoInteractions(repository);
    }

    @Test void T_01_13_aProductionProcessWithoutMasterKeyFailsToStart() {
        assertThatThrownBy(() -> new SigningKeys(repository, clock, profiles("prod"), ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OIDC_MASTER_KEY");
    }

    @Test void T_01_13_theEphemeralMasterKeyIsRandom() {
        var first = (SecretKeySpec) ReflectionTestUtils.getField(new SigningKeys(repository, clock, profiles("test"), ""), "master");
        var second = (SecretKeySpec) ReflectionTestUtils.getField(new SigningKeys(repository, clock, profiles("test"), ""), "master");

        assertThat(first.getEncoded()).hasSize(32).isNotEqualTo(new byte[32]);
        assertThat(first.getEncoded()).isNotEqualTo(second.getEncoded());
    }

    @Test void T_01_13_theFirstProcessWithAMasterKeyInitialisesTheSharedRing() {
        byte[] master = new byte[32];
        for (int i = 0; i < master.length; i++) { master[i] = (byte) (i + 1); }
        var stored = new AtomicReference<SigningKeyRing>();
        when(repository.findById("active")).thenAnswer(call -> Optional.ofNullable(stored.get()));
        doAnswer(call -> { stored.set(call.getArgument(0)); return null; }).when(repository).initialize(any());

        var keys = new SigningKeys(repository, clock, profiles("prod"), Base64.getEncoder().encodeToString(master));

        assertThat(stored.get()).isNotNull();
        assertThat(stored.get().id()).isEqualTo("active");
        assertThat(stored.get().generation()).isZero();
        assertThat(stored.get().rotatedAt()).isEqualTo(NOW);
        assertThat(keys.persistent()).isTrue();
        assertThat(keys.keys()).hasSize(2);
        verify(repository).initialize(any());
    }
}

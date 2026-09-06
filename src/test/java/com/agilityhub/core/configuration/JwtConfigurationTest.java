package com.agilityhub.core.configuration;

import com.agilityhub.core.identity.application.SigningKeys;
import com.agilityhub.core.identity.persistence.SigningKeyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtConfigurationTest {
    JwtConfiguration config = new JwtConfiguration();
    final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    @Test void T_01_13_ephemeralKeysOnlyInLocalTestAndMasterMustBe256Bits() {
        var repository = mock(SigningKeyRepository.class);
        for (String profile : new String[]{"local", "test"}) {
            var env = new MockEnvironment(); env.setActiveProfiles(profile);
            var keys = new SigningKeys(repository, clock, env, "");
            assertThat(keys.current().isPrivate()).isTrue();
            assertThat(keys.publicKeys().getKeys()).hasSize(2).allMatch(key -> !key.isPrivate());
            assertThatThrownBy(() -> new com.agilityhub.core.identity.application.RotateKeysCommand(keys)
                    .run(new org.springframework.boot.DefaultApplicationArguments())).isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
            keys.rotate();
            assertThatThrownBy(keys::rotate).isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
        }
        for (String profile : new String[]{"prod", "staging", "custom"}) {
            var env = new MockEnvironment(); env.setActiveProfiles(profile);
            assertThatThrownBy(() -> new SigningKeys(repository, clock, env, "")).isInstanceOf(IllegalStateException.class);
        }
        var env = new MockEnvironment(); env.setActiveProfiles("local", "prod");
        assertThatThrownBy(() -> new SigningKeys(repository, clock, env, "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SigningKeys(repository, clock, env, "invalid")).isInstanceOf(IllegalArgumentException.class);
        when(repository.findById("active")).thenReturn(java.util.Optional.of(new com.agilityhub.core.identity.persistence.SigningKeyRing("active", "broken", 0, clock.instant())));
        assertThatThrownBy(() -> new SigningKeys(repository, clock, env, java.util.Base64.getEncoder().encodeToString(new byte[32])))
                .isInstanceOf(IllegalStateException.class).hasMessage("Could not decrypt signing keys");
    }
    @Test void T_01_07_decoderRejectsWrongIssuerAudienceMissingExpiryAndIdTokens() {
        var env = new MockEnvironment(); env.setActiveProfiles("test");
        var keys = new SigningKeys(mock(SigningKeyRepository.class), clock, env, "");
        var encoder = config.jwtEncoder(keys);
        var clients = mock(org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository.class);
        when(clients.findByClientId("clubs-app")).thenReturn(mock(org.springframework.security.oauth2.server.authorization.client.RegisteredClient.class));
        var decoder = config.jwtDecoder(config.jwkSource(keys), clock, clients, "https://id.example.test");
        for (String invalid : new String[]{"issuer", "audience", "expiry", "subject", "id"}) {
            var claims = JwtClaimsSet.builder().issuer(invalid.equals("issuer") ? "https://wrong.example.test" : "https://id.example.test")
                    .audience(List.of(invalid.equals("audience") ? "other" : "clubs-app")).issuedAt(clock.instant());
            if (!invalid.equals("expiry")) { claims.expiresAt(clock.instant().plusSeconds(900)); }
            if (!invalid.equals("subject")) { claims.subject("account-a"); }
            if (invalid.equals("id")) { claims.claim("token_use", "id"); }
            String jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
            assertThatThrownBy(() -> decoder.decode(jwt)).isInstanceOf(JwtValidationException.class);
        }
    }
}

package com.agilityhub.core.configuration;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import static org.assertj.core.api.Assertions.*;

class JwtConfigurationTest {
    JwtConfiguration config = new JwtConfiguration();
    @Test void T_01_07_ephemeralKeysOnlyInLocalTestAndPemKeyHasStablePublicThumbprintKid() throws Exception {
        for (String profile : new String[]{"local", "test"}) {
            var env = new MockEnvironment(); env.setActiveProfiles(profile);
            assertThat(config.signingKey("", env).isPrivate()).isTrue();
        }
        for (String profile : new String[]{"prod", "staging", "custom"}) {
            var env = new MockEnvironment(); env.setActiveProfiles(profile);
            assertThatThrownBy(() -> config.signingKey("", env)).isInstanceOf(IllegalStateException.class);
        }
        var env = new MockEnvironment(); env.setActiveProfiles("local", "prod");
        assertThatThrownBy(() -> config.signingKey("", env)).isInstanceOf(IllegalStateException.class);
        RSAKey key = new RSAKeyGenerator(2048).generate();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10})
                .encodeToString(key.toPrivateKey().getEncoded()) + "\n-----END PRIVATE KEY-----";
        assertThat(config.signingKey(pem, env).getKeyID()).isEqualTo(key.computeThumbprint().toString());
        String publicPem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10})
                .encodeToString(key.toPublicKey().getEncoded()) + "\n-----END PUBLIC KEY-----";
        assertThatThrownBy(() -> config.signingKey(publicPem, env)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.signingKey("invalid", env)).isInstanceOf(Exception.class);
    }
    @Test void T_01_07_decoderRejectsCorrectlySignedWrongIssuerAudienceAndMissingExpiry() throws Exception {
        var env = new MockEnvironment(); env.setActiveProfiles("test");
        var key = config.signingKey("", env);
        var encoder = config.jwtEncoder(config.jwkSource(key));
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var decoder = config.jwtDecoder(key, Clock.fixed(now, ZoneOffset.UTC), "https://id.example.test");
        for (String invalid : new String[]{"issuer", "audience", "expiry", "subject"}) {
            var claims = JwtClaimsSet.builder().issuer(invalid.equals("issuer") ? "https://wrong.example.test" : "https://id.example.test")
                    .audience(List.of(invalid.equals("audience") ? "other" : "clubs-app")).issuedAt(now);
            if (!invalid.equals("expiry")) { claims.expiresAt(now.plusSeconds(900)); }
            if (!invalid.equals("subject")) { claims.subject("account-a"); }
            String jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
            assertThatThrownBy(() -> decoder.decode(jwt)).isInstanceOf(JwtValidationException.class);
        }
    }
}

package com.agilityhub.core.configuration;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;

@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {
    @Bean RSAKey signingKey(@Value("${identity.jwk-pem:}") String pem, Environment environment) throws Exception {
        RSAKey key;
        if (pem.isBlank()) {
            if (!environment.acceptsProfiles(Profiles.of("local", "test"))
                    || environment.acceptsProfiles(Profiles.of("staging", "prod"))) {
                throw new IllegalStateException("AUTH_JWK_PEM is required outside local/test");
            }
            key = new RSAKeyGenerator(2048).generate();
        } else {
            JWK parsed = JWK.parseFromPEMEncodedObjects(pem);
            if (!(parsed instanceof RSAKey rsa) || !rsa.isPrivate() || rsa.size() < 2048) {
                throw new IllegalStateException("AUTH_JWK_PEM must contain an RSA private key of at least 2048 bits");
            }
            key = rsa;
        }
        return new RSAKey.Builder(key).keyID(key.computeThumbprint().toString()).build();
    }
    @Bean JWKSource<SecurityContext> jwkSource(RSAKey key) { return new ImmutableJWKSet<>(new JWKSet(key)); }
    @Bean JwtEncoder jwtEncoder(JWKSource<SecurityContext> keys) { return new NimbusJwtEncoder(keys); }
    @Bean JwtDecoder jwtDecoder(RSAKey key, Clock clock, @Value("${identity.issuer}") String issuer) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(issuer), jwt -> {
            boolean valid = jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(clock.instant()) && jwt.getSubject() != null
                    && jwt.getAudience().stream().anyMatch(Set.of("clubs-app", "clubs-admin", "id-web")::contains);
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }));
        return decoder;
    }
}

package com.agilityhub.core.configuration;

import com.agilityhub.core.identity.application.SigningKeys;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {
    @Bean JWKSource<SecurityContext> jwkSource(SigningKeys keys) {
        return (selector, context) -> selector.select(keys.publicKeys());
    }
    @Bean JwtEncoder jwtEncoder(SigningKeys keys) {
        return parameters -> {
            var key = keys.current();
            var encoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(key)));
            var header = parameters.getJwsHeader() == null
                    ? JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                    : JwsHeader.from(parameters.getJwsHeader());
            return encoder.encode(JwtEncoderParameters.from(header.keyId(key.getKeyID()).build(), parameters.getClaims()));
        };
    }
    @Bean JwtDecoder jwtDecoder(JWKSource<SecurityContext> keys, Clock clock, RegisteredClientRepository clients,
                                 @Value("${identity.issuer}") String issuer) {
        var processor = new com.nimbusds.jwt.proc.DefaultJWTProcessor<SecurityContext>();
        processor.setJWSKeySelector(new com.nimbusds.jose.proc.JWSVerificationKeySelector<>(com.nimbusds.jose.JWSAlgorithm.RS256, keys));
        // The injected Clock, issuer and audience validator below own claim validation.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        var decoder = new NimbusJwtDecoder(processor);
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(issuer), jwt -> {
            boolean valid = !"id".equals(jwt.getClaimAsString("token_use")) && jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(clock.instant()) && jwt.getSubject() != null
                    && jwt.getAudience().stream().anyMatch(id -> clients.findByClientId(id) != null);
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }));
        return decoder;
    }
}

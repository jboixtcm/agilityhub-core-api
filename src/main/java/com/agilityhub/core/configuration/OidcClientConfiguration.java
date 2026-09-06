package com.agilityhub.core.configuration;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OidcClientConfiguration.Properties.class)
public class OidcClientConfiguration {
    @ConfigurationProperties("core.oidc")
    public record Properties(List<Client> clients, String loginUrl) { }
    public record Client(String id, String secret, List<String> redirectUris, List<String> postLogoutRedirectUris,
                         Set<String> scopes, Set<String> grants, boolean confidential) {
        @Override public String toString() { return "OidcClient[" + id + "]"; }
    }
    @Bean RegisteredClientRepository registeredClients(Properties properties, org.springframework.core.env.Environment environment) {
        var bcrypt = new BCryptPasswordEncoder();
        return new InMemoryRegisteredClientRepository(properties.clients().stream().map(source -> {
            var client = RegisteredClient.withId(source.id()).clientId(source.id())
                    .clientSettings(ClientSettings.builder().requireProofKey(!source.confidential()).requireAuthorizationConsent(false).build());
            if (source.confidential()) {
                if ((source.secret() == null || source.secret().isBlank())
                        && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("staging", "prod"))) {
                    throw new IllegalStateException("Confidential OIDC clients require a secret from the environment");
                }
                // Missing local secrets disable authentication without shipping a usable default credential.
                client.clientSecret(bcrypt.encode(source.secret() == null || source.secret().isBlank()
                                ? com.agilityhub.core.identity.application.TokenService.opaque() : source.secret()))
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            } else { client.clientAuthenticationMethod(ClientAuthenticationMethod.NONE); }
            source.redirectUris().forEach(uri -> { validateUri(uri); client.redirectUri(uri); });
            source.postLogoutRedirectUris().forEach(uri -> { validateUri(uri); client.postLogoutRedirectUri(uri); });
            source.scopes().forEach(client::scope);
            source.grants().forEach(grant -> client.authorizationGrantType(new AuthorizationGrantType(grant)));
            return client.build();
        }).toList());
    }
    private static void validateUri(String value) {
        var uri = java.net.URI.create(value);
        if (!uri.isAbsolute() || uri.getFragment() != null || uri.getUserInfo() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()))) {
            throw new IllegalArgumentException("OIDC redirect URIs require HTTPS or a loopback HTTP address, without fragments or user info");
        }
    }
}

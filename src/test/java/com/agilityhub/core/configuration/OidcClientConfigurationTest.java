package com.agilityhub.core.configuration;

import com.agilityhub.core.identity.application.TokenDelivery;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import static org.assertj.core.api.Assertions.*;

class OidcClientConfigurationTest {
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(OidcClientConfiguration.class).withPropertyValues(
                "core.oidc.clients[0].id=browser", "core.oidc.clients[0].redirect-uris[0]=https://example.test/callback",
                "core.oidc.clients[0].post-logout-redirect-uris[0]=https://example.test/",
                "core.oidc.clients[0].scopes[0]=openid", "core.oidc.clients[0].grants[0]=authorization_code");
    }
    @Test void T_01_27_deliveryIsValidatedAtStartupAndStoredPerClient() {
        for (String delivery : new String[]{"COOKIE", "BODY"}) {
            runner().withPropertyValues("core.oidc.clients[0].token-delivery=" + delivery).run(context -> {
                assertThat(context).hasNotFailed();
                var client = context.getBean(RegisteredClientRepository.class).findByClientId("browser");
                assertThat(TokenDelivery.cookie(client)).isEqualTo(delivery.equals("COOKIE"));
            });
        }
        runner().withPropertyValues("core.oidc.clients[0].token-delivery=UNKNOWN").run(context -> assertThat(context).hasFailed());
        runner().run(context -> assertThat(context).hasFailed());
    }
}

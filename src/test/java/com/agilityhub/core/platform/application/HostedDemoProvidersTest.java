package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.PlatformConsoleProjection;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostedDemoProvidersTest {
    @Test void liveConfigurationIsRejectedBeforeLockSecretsOrWrites() {
        var clubs = mock(ClubRepository.class); var changes = mock(PlatformClubChanges.class);
        var vault = mock(ConsolePaymentAccess.class); var projection = mock(PlatformConsoleProjection.class);
        var providers = new PlatformProviders(changes, vault, projection, vault, Clock.systemUTC());
        new ApplicationContextRunner().withPropertyValues("core.hosted-demo.enabled=true")
                .withBean(ClubRepository.class, () -> clubs).withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
                .withBean(PlatformProviders.class, () -> providers).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThatThrownBy(() -> providers.update("STRIPE", Map.of("mode", "live", "publishableKey", "pk_live_example")))
                            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.PROVIDER_CONFIG_INVALID));
                    verifyNoInteractions(clubs, changes, vault, projection);
                });
    }
}

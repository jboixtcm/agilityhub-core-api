package com.agilityhub.core.configuration;

import com.agilityhub.core.identity.application.PasswordHasher;
import com.agilityhub.core.identity.application.SeedTestAccountsCommand;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import java.time.Clock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SeedProfileTest {
    @ParameterizedTest @ValueSource(strings = {"local", "test", "prod", "staging", "local,prod", "test,staging"})
    void T_01_02_seedCommandExistsOnlyInLocalOrTestWithoutDeploymentProfiles(String profile) {
        new ApplicationContextRunner().withUserConfiguration(SeedTestAccountsCommand.class)
                .withPropertyValues("spring.profiles.active=" + profile, "identity.seed-password=")
                .withBean(AccountRepository.class, () -> mock(AccountRepository.class))
                .withBean(MembershipRepository.class, () -> mock(MembershipRepository.class))
                .withBean(PasswordHasher.class, () -> mock(PasswordHasher.class))
                .withBean(ClubConfigService.class, () -> mock(ClubConfigService.class))
                .withBean(Clock.class, Clock::systemUTC)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(SeedTestAccountsCommand.class))
                            .hasSize(profile.equals("local") || profile.equals("test") ? 1 : 0);
                });
    }
}

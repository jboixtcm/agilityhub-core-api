package com.agilityhub.core.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

/** E3-T09 step 2 (M17): staging and production refuse to start without the trusted proxy pattern; local and test do not need it. */
class TrustedProxyConfigurationTest {
    private ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner().withUserConfiguration(TrustedProxyConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile));
    }

    @Test void R_04_20_stagingAndProdFailAtStartupWithoutTheTrustedProxyPattern() {
        for (String profile : new String[]{"staging", "prod"}) {
            runner(profile).withPropertyValues("server.tomcat.remoteip.internal-proxies=").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("TRUSTED_PROXY_PATTERN");
            });
            runner(profile).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test void R_04_20_stagingStartsWithThePatternAndLocalOrTestWithout() {
        runner("staging").withPropertyValues("server.tomcat.remoteip.internal-proxies=172\\.18\\.\\d{1,3}\\.\\d{1,3}")
                .run(context -> assertThat(context).hasNotFailed());
        for (String profile : new String[]{"local", "test"}) {
            runner(profile).withPropertyValues("server.tomcat.remoteip.internal-proxies=").run(context -> assertThat(context).hasNotFailed());
        }
    }
}

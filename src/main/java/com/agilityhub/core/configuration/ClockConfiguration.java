package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.application.OffsetClock;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The only source of time (S15 R-15-22). The movable clock of `POST /test/clock` exists under the profile expression of every
 * other test-only bean (E5-T27 step 6, audit A7-05): `local` or `test`, never together with `staging` or `prod`.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {
    /** The test-only beans' profile expression (`FakeCheckoutGateway`, `ExportConfiguration`, `AttachmentConfiguration`, …). */
    public static final String TEST_ONLY = "(local | test) & !staging & !prod";

    @Bean
    @Profile("!(" + TEST_ONLY + ")")
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile(TEST_ONLY)
    OffsetClock movableClock() {
        return new OffsetClock(Clock.systemUTC());
    }
}

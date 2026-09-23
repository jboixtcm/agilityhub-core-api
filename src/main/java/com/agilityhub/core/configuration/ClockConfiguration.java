package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.application.OffsetClock;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The only source of time (S15 R-15-22); `local` and `test` get a movable clock for `POST /test/clock`. */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    @Profile("!local & !test")
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile({"local", "test"})
    OffsetClock movableClock() {
        return new OffsetClock(Clock.systemUTC());
    }
}

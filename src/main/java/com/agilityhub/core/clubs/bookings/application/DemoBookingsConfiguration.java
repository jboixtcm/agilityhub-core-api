package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.DemoClassBookingRepository;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.agilityhub.core.clubs.scheduling.application.ports.*;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

/** Local/test only; ordered before the null-object defaults. Any application-registered ClassBookingsPort (E5) wins. */
@AutoConfiguration(before = SchedulingPortDefaults.class)
@Profile({"local", "test"})
public class DemoBookingsConfiguration {
    @Bean @ConditionalOnMissingBean(ClassBookingsPort.class)
    DemoClassBookings demoClassBookings(DemoClassBookingRepository bookings, ClassSessionService sessions, Clock clock) {
        return new DemoClassBookings(bookings, sessions, clock);
    }
}

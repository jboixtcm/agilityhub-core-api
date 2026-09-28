package com.agilityhub.core.platform.api;

import com.agilityhub.core.shared.application.MutableClock;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * S15 WP-15-E: moves the application clock for end-to-end sessions. Exists only under `local` or `test`, never together with
 * `staging` or `prod` (E5-T27 step 6, audit A7-05: the profile expression of every other test-only bean), is not published in
 * the OpenAPI snapshot and needs no tenant. Its own `@PreAuthorize` denies it whenever that expression is false.
 */
@RestController
@Profile(TestClockController.PROFILES)
@Hidden
public class TestClockController {
    static final String PROFILES = "(local | test) & !staging & !prod";
    private final Clock clock;
    public TestClockController(Clock clock) { this.clock = clock; }

    public record ClockRequest(Instant instant, Long advanceSeconds) { }
    public record ClockResponse(Instant now) { }

    @PostMapping("/api/v1/test/clock")
    @PreAuthorize("@environment.acceptsProfiles(T(org.springframework.core.env.Profiles).of('" + PROFILES + "'))")
    public ClockResponse move(@RequestBody ClockRequest request) {
        if (!(clock instanceof MutableClock mutable) || (request.instant() == null) == (request.advanceSeconds() == null)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        if (request.instant() != null) { mutable.setInstant(request.instant()); }
        else { mutable.advance(Duration.ofSeconds(request.advanceSeconds())); }
        return new ClockResponse(clock.instant());
    }
}

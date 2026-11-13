package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.AccountMembershipRepository;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.MembershipRepository;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.shared.application.RateLimits;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * WEB-01 (owner decision 13-11-2026, S03 R-03-06): the D10 welcome e-mail is limited per address and per club, with the production
 * capacities (10/h per address, 60/h per club), and never takes a token from the magic-link IP bucket. A real {@link RateLimits}.
 */
class WelcomeRateLimitTest {
    static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    final RateLimits limits = new RateLimits(true, Map.of(
            RateLimits.Route.MAGIC_LINK_EMAIL, new RateLimits.Limit(10, Duration.ofHours(1)),
            RateLimits.Route.MAGIC_LINK_IP, new RateLimits.Limit(60, Duration.ofHours(1)),
            RateLimits.Route.ACCESS_RESEND_CLUB, new RateLimits.Limit(60, Duration.ofHours(1))), Clock.fixed(NOW, ZoneOffset.UTC));
    final CensusIdentityService service = new CensusIdentityService(mock(AccountRepository.class), mock(MembershipRepository.class),
            mock(MagicLinkService.class), limits, mock(CensusClubSettings.class), mock(AccountMembershipRepository.class));

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void WEB_01_aClubSendsSixtyWelcomesAnHourWhateverTheAdministratorsAddressAndAnotherClubHasItsOwn() {
        try (var club = TenantContext.open("club-a")) {
            for (int i = 0; i < 60; i++) { service.limitWelcome("member" + i + "@example.test"); }
            assertThatThrownBy(() -> service.limitWelcome("late@example.test")).isInstanceOfSatisfying(ApiException.class, failure -> {
                assertThat(failure).hasMessage("RATE_LIMITED");
                assertThat(failure.details()).containsEntry("retryAfter", 3600L);
            });
        }
        try (var other = TenantContext.open("club-b")) {
            assertThatCode(() -> service.limitWelcome("late@example.test")).doesNotThrowAnyException();
        }
        // The members' own sign-in requests from the club's network keep their whole IP bucket.
        assertThat(limits.retryAfter(RateLimits.Route.MAGIC_LINK_IP, "198.51.100.10")).isZero();
    }

    @Test void WEB_01_theAddressKeepsItsTenAnHour() {
        try (var club = TenantContext.open("club-a")) {
            for (int i = 0; i < 10; i++) { service.limitWelcome("laura@example.test"); }
            assertThatThrownBy(() -> service.limitWelcome("laura@example.test")).hasMessage("RATE_LIMITED");
            assertThatCode(() -> service.limitWelcome("marc@example.test")).doesNotThrowAnyException();
        }
    }

    @Test void WEB_01_aWelcomeNeedsAClub() {
        assertThatThrownBy(() -> service.limitWelcome("laura@example.test")).isInstanceOf(RuntimeException.class);
    }
}

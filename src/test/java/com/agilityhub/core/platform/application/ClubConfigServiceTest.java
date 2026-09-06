package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.Parameter;
import com.agilityhub.core.platform.persistence.ParameterRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.DefaultClubClock;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ClubConfigServiceTest {
    final ClubRepository clubs = mock(ClubRepository.class);
    final ParameterRepository parameters = mock(ParameterRepository.class);
    final ClubConfigService service = new ClubConfigService(clubs, parameters, new ParameterCatalog(new ObjectMapper()), new CountryProfileRegistry());
    Parameter override(String key, Object value, String scope) {
        return new Parameter("parameter-" + scope, "club-a", key, value, "int", scope == null ? "club" : "ring", scope,
                List.of(new Parameter.History(value, Instant.parse("2030-01-01T00:00:00Z"), "account-a", "Example")), null, Instant.parse("2030-01-01T00:00:00Z"));
    }
    void club() { when(clubs.findById("club-a")).thenReturn(Optional.of(PlatformFixtures.club("club-a", "a.example.test"))); }
    @Test void T_02_01_scopeOverridesClubOverridesDefaultAndInvalidOverrideIsLogged(CapturedOutput output) {
        club();
        when(parameters.findAll()).thenReturn(List.of(override("training.capacityPerRingSlot", 2, null),
                override("training.capacityPerRingSlot", 3L, "ring-small"), override("training.capacityPerRingSlot", "PRIVATE_INVALID_VALUE", "ring-bad"),
                override("signup.enabled", false, "ring-small"), override("retired.key", 1, null)));
        var config = service.get("club-a");
        assertThat(config.get("training.capacityPerRingSlot", Integer.class)).isEqualTo(2);
        assertThat(config.get("training.capacityPerRingSlot", "ring-small", Integer.class)).isEqualTo(3);
        assertThat(config.get("training.capacityPerRingSlot", "ring-bad", Integer.class)).isEqualTo(2);
        assertThat(config.get("training.capacityPerRingSlot", "unknown", Integer.class)).isEqualTo(2);
        assertThat(config.get("signup.enabled", "ring-small", Boolean.class)).isTrue();
        assertThat(config.get("bookings.lateCancelThresholdMinutes", Integer.class)).isEqualTo(240);
        assertThat(config.get("billing.entryFeePerDog", Money.class)).isEqualTo(new Money(10000, "EUR"));
        assertThat(config.get("signup.rateLimit", Map.class)).containsEntry("identityChecksPerHour", 10).containsEntry("signupPerDay", 20);
        assertThatThrownBy(() -> config.get("invented.key", String.class)).isInstanceOf(ApiException.class);
        assertThat(output).contains("ParameterInvalidOverride", "club-a", "training.capacityPerRingSlot").doesNotContain("PRIVATE_INVALID_VALUE");
        assertThat(TenantContext.current()).isNull();
        when(parameters.findAll()).thenReturn(List.of(override("training.capacityPerRingSlot", false, null)));
        service.invalidate("club-a"); assertThat(service.get("club-a").get("training.capacityPerRingSlot", Integer.class)).isEqualTo(1);
    }
    @Test void T_02_01_cacheIsImmutableAndTenantAccessCheckedOnCacheHits() {
        club(); when(parameters.findAll()).thenReturn(List.of());
        var first = service.get("club-a"); assertThat(service.get("club-a")).isSameAs(first);
        verify(parameters, times(1)).findAll();
        assertThatThrownBy(() -> first.parameters().put("signup.enabled", false)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.get("club.openingHours", Map.class).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.modules().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.club().locales().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.club().theme().ringPalette().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.club().pwa().icons().clear()).isInstanceOf(UnsupportedOperationException.class);
        try (var scope = TenantContext.open("club-b")) { assertThatThrownBy(() -> service.get("club-a")).isInstanceOf(ApiException.class); }
        service.invalidate("club-a"); assertThat(service.get("club-a")).isNotSameAs(first);
        assertThatThrownBy(() -> service.get("missing")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.CLUB_NOT_FOUND));
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_02_15_clubClockAndWeekOpeningUseClubLocalTime() {
        when(clubs.findById("club-a")).thenReturn(Optional.of(PlatformFixtures.club(
                "club-a", "a.example.test", "America/Argentina/Buenos_Aires")));
        when(parameters.findAll()).thenReturn(List.of());
        var clock = new DefaultClubClock(Clock.fixed(Instant.parse("2030-01-06T23:00:00Z"), ZoneId.of("UTC")), service);
        var week = service.get("club-a").get("bookings.weekOpensAt", Map.class);
        assertThat(clock.now("club-a").toLocalTime().toString()).isEqualTo(week.get("time"));
        assertThat(clock.now("club-a").getDayOfWeek().name()).isEqualTo(week.get("dayOfWeek"));
        var opening = clock.today("club-a").atTime(java.time.LocalTime.parse((String) week.get("time")))
                .atZone(service.timeZone("club-a"));
        assertThat(opening.toInstant()).isEqualTo(Instant.parse("2030-01-06T23:00:00Z"));
    }
    @Test void T_02_06_hostCacheSupportsNegativeEntriesAndInvalidation() {
        var resolver = new HostTenantResolver(clubs);
        when(clubs.findByHost("a.example.test")).thenReturn(Optional.of(PlatformFixtures.club("club-a", "a.example.test")));
        assertThat(resolver.resolve("A.EXAMPLE.TEST:8080")).contains("club-a");
        assertThat(resolver.resolve("a.example.test")).contains("club-a"); verify(clubs, times(1)).findByHost("a.example.test");
        assertThat(resolver.resolve("missing.example.test")).isEmpty(); assertThat(resolver.resolve("missing.example.test")).isEmpty();
        verify(clubs, times(1)).findByHost("missing.example.test");
        assertThat(resolver.resolve(null)).isEmpty();
        resolver.invalidate(); resolver.resolve("a.example.test"); verify(clubs, times(2)).findByHost("a.example.test");
    }
}

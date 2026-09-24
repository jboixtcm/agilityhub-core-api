package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

/** E5-T14 (review E5-T09 #10): the seed actor runs only in the local/test profiles, like DemoPlanningService. */
class DemoSeedActorTest {
    static MockEnvironment environment(String... profiles) { var environment = new MockEnvironment(); environment.setActiveProfiles(profiles); return environment; }

    @Test void E5_T14_theSeedActorRefusesToRunOutsideTheLocalAndTestProfiles() {
        for (var environment : List.of(environment(), environment("prod"), environment("staging"), environment("local", "prod"), environment("test", "staging"))) {
            assertThatThrownBy(() -> DemoSeedActor.as(environment, "account", "ADMIN", () -> fail("must not run")))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        }
        assertThatThrownBy(() -> DemoSeedActor.as(null, "account", "ADMIN", () -> "work")).as("no registered environment: fail closed")
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void E5_T14_inLocalOrTestTheWorkRunsAsTheSeedAccountAndTheContextIsRestored() {
        for (String profile : List.of("local", "test")) {
            var seen = DemoSeedActor.as(environment(profile), "account-a", "INSTRUCTOR", () -> List.of(SecurityContextHolder.getContext().getAuthentication().getName(),
                    CurrentUser.current().origin()));
            assertThat(seen).containsExactly("account-a", DomainEvent.Origin.INSTRUCTOR);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(CurrentUser.current()).isNull();
        }
        var guard = new DemoSeedActor.ProfileGuard(); guard.setEnvironment(environment("test"));
        assertThat(DemoSeedActor.as("account-b", "MEMBER", () -> CurrentUser.current().origin())).isEqualTo(DomainEvent.Origin.APP);
        guard.setEnvironment(environment("prod"));
        assertThatThrownBy(() -> DemoSeedActor.as("account-b", "MEMBER", () -> "work")).isInstanceOf(ApiException.class);
        guard.setEnvironment(environment("test"));
    }
}

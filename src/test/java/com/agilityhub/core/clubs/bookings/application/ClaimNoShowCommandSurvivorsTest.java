package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link ClaimNoShowCommand} (S10 R-10-06, T-10-26): `bin/core attendance:claim-no-show` without
 * `--club` claims every active club, each in its own tenant, and prints one line per club with the event id only when a
 * batch was published.
 */
class ClaimNoShowCommandSurvivorsTest {
    static final LocalDate TODAY = LocalDate.parse("2026-08-04");

    final ClubConfigService clubs = mock(ClubConfigService.class);
    final NoShowNoticeClaims claims = mock(NoShowNoticeClaims.class);
    final ClaimNoShowCommand command = new ClaimNoShowCommand(clubs, claims);

    @Test void T_10_26_theCommandIsTheBinCoreVerb() {
        assertThat(command.name()).isEqualTo("attendance:claim-no-show");
    }

    @Test void T_10_26_withoutAClubEveryActiveClubIsClaimedAndReported() {
        when(clubs.activeClubIds()).thenReturn(List.of("club-a", "club-b"));
        when(claims.today()).thenReturn(TODAY);
        var tenants = new ArrayList<String>();
        when(claims.claim(TODAY)).thenAnswer(inv -> {
            tenants.add(TenantContext.require());
            return tenants.size() == 1
                    ? new NoShowNoticeClaims.Claim("event-19", List.of("attendance-1", "attendance-2"), List.of("booking-1", "booking-2"))
                    : new NoShowNoticeClaims.Claim(null, List.of(), List.of());
        });

        var output = new ByteArrayOutputStream(); var original = System.out;
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        try { command.run(new DefaultApplicationArguments("--core.command=attendance:claim-no-show")); }
        finally { System.setOut(original); }

        assertThat(tenants).containsExactly("club-a", "club-b");
        assertThat(output.toString(StandardCharsets.UTF_8).lines().toList())
                .containsExactly("No-show notices claimed: 2 (NoShowNoticeDue event-19)", "No-show notices claimed: 0");
    }
}

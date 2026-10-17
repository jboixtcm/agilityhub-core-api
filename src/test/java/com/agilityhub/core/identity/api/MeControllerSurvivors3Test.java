package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.AccountService;
import com.agilityhub.core.identity.application.IdentityService;
import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.identity.application.OnboardingService;
import com.agilityhub.core.identity.application.PasswordService;
import com.agilityhub.core.identity.application.TokenService;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.MemberIdentityAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivor (session 10) of {@link MeController#me} (S01 R-01-15, T-01-25): the club bootstrap of a club with the BILLING
 * module carries the member's payment method, and a club without it does not. The filter opens the {@link CurrentUser} of the
 * bearer (CurrentUserFilter:42); collaborators are mocks; fictional data.
 */
class MeControllerSurvivors3Test {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final IdentityService identities = mock(IdentityService.class);
    final ClubConfigService clubs = mock(ClubConfigService.class);
    final MemberIdentityAccess census = mock(MemberIdentityAccess.class);
    final MeController controller = new MeController(identities, clubs, mock(AccountService.class), mock(PasswordService.class),
            mock(TokenService.class), mock(OnboardingService.class), mock(IdentityTransactions.class), mock(RefreshCookies.class), census);

    @BeforeEach void setUp() {
        TenantContext.clear();
        TenantContext.open(CLUB);
        var account = new Account("acc-1", "laura@example.test", "Laura Example", "ca", null, Set.of(), Account.Status.ACTIVE,
                new Account.Security(0, null, null, 0), Map.of(), false, NOW.minusSeconds(86_400 * 90L));
        var membership = new Membership("ms-1", "acc-1", CLUB, "member-1", Set.of(Role.MEMBER), Membership.Status.ACTIVE, null, false, null,
                NOW.minusSeconds(86_400 * 30L), NOW.minusSeconds(3_600));
        when(identities.current("acc-1")).thenReturn(new IdentityService.Session(account, membership));
        when(census.bootstrap("member-1")).thenReturn(new MemberIdentityAccess.Bootstrap(null, null, null));
        when(census.paymentMethod("member-1")).thenReturn(new MemberIdentityAccess.PaymentMethod("CARD", false));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    private MeResponse me() {
        var jwt = Jwt.withTokenValue("eyJ.fictional.sig").header("alg", "RS256").subject("acc-1").claim("azp", "clubs-app")
                .claim("clubId", CLUB).claim("roles", List.of("MEMBER")).claim("activeProfile", "MEMBER").claim("memberId", "member-1").build();
        try (var scope = CurrentUser.open(new CurrentUser("acc-1", "Laura Example", null, DomainEvent.Origin.APP))) {
            return controller.me(jwt);
        }
    }

    @Test void T_01_25_aBillingClubBootstrapCarriesTheMembersPaymentMethod() {
        when(clubs.get(CLUB)).thenReturn(new ClubConfig(null, Map.of(), Set.of(Module.BILLING), null, Map.of()));

        assertThat(me().paymentMethod()).isEqualTo(new MeResponse.MemberPaymentMethod("CARD", false));
    }

    @Test void T_01_25_aClubWithoutBillingHasNoPaymentMethodInItsBootstrap() {
        when(clubs.get(CLUB)).thenReturn(new ClubConfig(null, Map.of(), Set.of(), null, Map.of()));

        assertThat(me().paymentMethod()).isNull();
    }
}

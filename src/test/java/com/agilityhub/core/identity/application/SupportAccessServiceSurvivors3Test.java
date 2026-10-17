package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.ImpersonationGrant;
import com.agilityhub.core.identity.persistence.ImpersonationGrantRepository;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors (session 11) of {@link SupportAccessService#validate} (S17 R-17-10, T-17-09): a support token whose grant
 * no longer exists, or whose platform account no longer exists, is refused `401 UNAUTHENTICATED` at its next request, never with
 * an unhandled error. Collaborators are mocks; fictional data.
 */
class SupportAccessServiceSurvivors3Test {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");

    final ImpersonationGrantRepository grants = mock(ImpersonationGrantRepository.class);
    final AccountRepository accounts = mock(AccountRepository.class);
    final SupportAccessService service = new SupportAccessService(grants, accounts, mock(AuthSettings.class), mock(EventPublisher.class),
            mock(JwtEncoder.class), Clock.fixed(NOW, ZoneOffset.UTC), "https://id.example.test");
    final Jwt token = Jwt.withTokenValue("eyJ.fictional.sig").header("alg", "RS256").subject("platform-1").jti("grant-1")
            .claim("clubId", CLUB).claim("roles", List.of("ADMIN")).claim("actorAccountId", "platform-1").claim("support", true).build();

    @BeforeEach void openClub() { TenantContext.clear(); TenantContext.open(CLUB); }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void T_17_09_R_17_10_aSupportTokenWhoseGrantIsGoneIsUnauthenticated() {
        when(grants.findById("grant-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(token, false)).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test void T_17_09_R_17_10_aSupportTokenWhosePlatformAccountIsGoneIsUnauthenticated() {
        when(grants.findById("grant-1")).thenReturn(Optional.of(new ImpersonationGrant("grant-1", CLUB, "platform-1", null, null,
                "Ticket 42", NOW.plusSeconds(1_800), null, ImpersonationGrant.Type.SUPPORT)));
        when(accounts.findById("platform-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(token, false)).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}

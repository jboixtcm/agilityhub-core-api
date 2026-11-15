package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;

/**
 * AUTH-01 (S01 R-01-18, owner decision of 13 Nov 2026): a staff caller (ADMIN, INSTRUCTOR, AGILITYHUB_ADMIN) needs a sign-in
 * (`auth_time`) of the last 15 minutes; a stale or unknown one is `403 RECENT_LOGIN_REQUIRED {maxAgeMinutes: 15}`; a member is never
 * asked. Fictional tokens; no Spring context.
 */
class RecentLoginsTest {
    static final Instant NOW = Instant.parse("2026-11-13T20:00:00Z");
    final RecentLogins logins = new RecentLogins(Clock.fixed(NOW, ZoneOffset.UTC));

    static JwtAuthenticationToken token(Object authTime, String... roles) {
        var builder = Jwt.withTokenValue("access-example").header("alg", "RS256").subject("account-a").issuedAt(NOW.minusSeconds(30));
        if (authTime != null) { builder.claim("auth_time", authTime); }
        return new JwtAuthenticationToken(builder.build(), Arrays.stream(roles).map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
    }
    void refused(JwtAuthenticationToken token) {
        assertThatThrownBy(() -> logins.require(token)).isInstanceOfSatisfying(ApiException.class, refusal -> {
            assertThat(refusal.code()).isEqualTo(ErrorCode.RECENT_LOGIN_REQUIRED);
            assertThat(refusal.details()).containsEntry("maxAgeMinutes", 15L);
        });
    }

    @Test void AUTH_01_aStaffSignInCountsAsRecentForFifteenMinutes() {
        assertThat(RecentLogins.WINDOW).isEqualTo(Duration.ofMinutes(15));
        for (String role : List.of("ADMIN", "INSTRUCTOR", "AGILITYHUB_ADMIN")) {
            assertThatCode(() -> logins.require(token(NOW.minus(Duration.ofMinutes(15)).getEpochSecond(), role, "MEMBER"))).as(role).doesNotThrowAnyException();
            refused(token(NOW.minus(Duration.ofMinutes(15)).minusSeconds(1).getEpochSecond(), role, "MEMBER"));
        }
    }

    @Test void AUTH_01_aStaffTokenWithoutASignInTimeIsNeverRecent() {
        refused(token(null, "ADMIN"));
    }

    @Test void AUTH_01_aMemberIsNeverAsked() {
        assertThatCode(() -> logins.require(token(NOW.minus(Duration.ofDays(20)).getEpochSecond(), "MEMBER"))).doesNotThrowAnyException();
        assertThatCode(() -> logins.require(token(null, "MEMBER"))).doesNotThrowAnyException();
    }

    @Test void AUTH_01_onlyABearerTokenHasASignIn() {
        assertThatCode(() -> logins.require((org.springframework.security.core.Authentication) null)).doesNotThrowAnyException();
        assertThatCode(() -> logins.require(new UsernamePasswordAuthenticationToken("account-a", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))))
                .doesNotThrowAnyException();
    }

    @Test void AUTH_01_theSignInTimeIsReadInEachFormTheDecoderGives() {
        Instant signedIn = NOW.minusSeconds(60);
        assertThat(RecentLogins.authTime(token(signedIn.getEpochSecond()).getToken())).isEqualTo(signedIn);
        assertThat(RecentLogins.authTime(token(signedIn).getToken())).isEqualTo(signedIn);
        assertThat(RecentLogins.authTime(token(java.util.Date.from(signedIn)).getToken())).isEqualTo(signedIn);
        assertThat(RecentLogins.authTime(token("yesterday").getToken())).isNull();
        assertThat(RecentLogins.authTime(token(null).getToken())).isNull();
    }
}

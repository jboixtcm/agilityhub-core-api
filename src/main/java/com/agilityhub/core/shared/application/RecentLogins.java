package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * AUTH-01 (S01 R-01-18, owner decision of 13 Nov 2026): the recent sign-in a staff caller needs for the actions of {@link RecentLogin}.
 * Recent means that the token's `auth_time` (the password, sign-in link or OIDC login that opened the session; a refresh, a profile
 * switch and a handoff keep it) is at most {@link #WINDOW} old. A token without `auth_time` is never recent. A member (no staff
 * role) is not asked: the decision covers the back office's staff, and a member's own data export stays as it was.
 */
@Component
public class RecentLogins {
    /** AUTH-01: how long a sign-in counts as recent; a technical constant, as the access token's 15 minutes. */
    public static final Duration WINDOW = Duration.ofMinutes(15);
    /** The authorities of the staff roles: a club's ADMIN and INSTRUCTOR, and the platform's AGILITYHUB_ADMIN. */
    static final Set<String> STAFF = Set.of("ROLE_ADMIN", "ROLE_INSTRUCTOR", "ROLE_AGILITYHUB_ADMIN");
    private final Clock clock;
    public RecentLogins(Clock clock) { this.clock = clock; }

    /** Refuses the current caller with `403 RECENT_LOGIN_REQUIRED {maxAgeMinutes}` when it is staff and its sign-in is not recent. */
    public void require() { require(SecurityContextHolder.getContext().getAuthentication()); }
    void require(Authentication authentication) {
        // Only a bearer token has a sign-in; the route's own rules decide any other caller (the security chain refuses an anonymous one).
        if (!(authentication instanceof JwtAuthenticationToken token) || !staff(token)) { return; }
        Instant signedIn = authTime(token.getToken());
        if (signedIn == null || signedIn.isBefore(clock.instant().minus(WINDOW))) {
            throw new ApiException(ErrorCode.RECENT_LOGIN_REQUIRED, Map.of("maxAgeMinutes", WINDOW.toMinutes()));
        }
    }
    static boolean staff(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(STAFF::contains);
    }
    /** The token's `auth_time` (epoch seconds, as `TokenService` writes it), or null. */
    public static Instant authTime(Jwt jwt) {
        Object value = jwt.getClaims().get("auth_time");
        if (value instanceof Instant instant) { return instant; }
        if (value instanceof Number seconds) { return Instant.ofEpochSecond(seconds.longValue()); }
        if (value instanceof java.util.Date date) { return date.toInstant(); }
        return null;
    }
}

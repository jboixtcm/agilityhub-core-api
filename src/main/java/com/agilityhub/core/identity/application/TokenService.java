package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.identity.domain.LoginLockout;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    public static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final IdentityService identities;
    private final AccountRepository accounts;
    private final MembershipRepository memberships;
    private final RefreshTokenRepository refreshTokens;
    private final AccountSessionRepository accountSessions;
    private final MagicLinkTokenRepository magicLinks;
    private final AuthSettings settings;
    private final JwtEncoder encoder;
    private final Clock clock;
    private final IdentityTransactions transactions;
    private final String issuer;
    private final SecurityEvents securityEvents;
    private final EventPublisher events;

    public TokenService(IdentityService identities, AccountRepository accounts, MembershipRepository memberships,
                        RefreshTokenRepository refreshTokens, AccountSessionRepository accountSessions, MagicLinkTokenRepository magicLinks,
                        AuthSettings settings, JwtEncoder encoder, Clock clock, IdentityTransactions transactions,
                        @Value("${identity.issuer}") String issuer, SecurityEvents securityEvents, EventPublisher events) {
        this.identities = identities; this.accounts = accounts; this.memberships = memberships; this.refreshTokens = refreshTokens;
        this.accountSessions = accountSessions; this.magicLinks = magicLinks; this.settings = settings; this.encoder = encoder;
        this.clock = clock; this.transactions = transactions; this.issuer = issuer; this.securityEvents = securityEvents; this.events = events;
    }
    public Tokens password(String email, String password, String clientId) { return password(email, password, clientId, null); }
    public Tokens password(String email, String password, String clientId, String userAgent) {
        var authenticated = identities.authenticate(email, password);
        return transactions.run(() -> {
            var session = identities.current(authenticated.account().id());
            // A password change between credential verification and issuance must not authorize a new session.
            if (!java.util.Objects.equals(session.account().passwordHash(), authenticated.account().passwordHash())) {
                throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
            }
            return login(session, clientId, userAgent);
        });
    }
    public Tokens magicLink(String value, String clientId, String userAgent) {
        var outcome = transactions.run(() -> {
            var token = magicLinks.find(digest(value), clientId).orElse(null);
            if (token == null || token.usedAt() != null || !token.expiresAt().isAfter(clock.instant())) {
                return new Outcome(null, ErrorCode.MAGIC_LINK_INVALID, token == null ? null : token.accountId());
            }
            var session = identities.current(token.accountId());
            accounts.touchSessions(token.accountId());
            if (!magicLinks.consume(token.id(), clock.instant())) { return new Outcome(null, ErrorCode.MAGIC_LINK_INVALID, token.accountId()); }
            accounts.verifyEmail(token.accountId(), clock.instant());
            accounts.lockout(token.accountId(), LoginLockout.empty());
            return new Outcome(login(session, clientId, userAgent), null, token.accountId());
        });
        if (outcome.error() != null) {
            securityEvents.record(SecurityEvents.Type.MAGIC_LINK_INVALID, outcome.accountId(), TenantContext.current());
            throw new ApiException(outcome.error());
        }
        return outcome.tokens();
    }
    /** Called inside the handoff consumption transaction. */
    Tokens handoff(IdentityService.Session session, String clientId, String userAgent) { return login(session, clientId, userAgent); }
    private Tokens login(IdentityService.Session session, String clientId, String userAgent) {
        accounts.touchSessions(session.account().id());
        Instant now = clock.instant();
        var live = accountSessions.active(session.account().id(), session.account().familyVersion(), now);
        int max = settings.integer("auth.maxSessions");
        for (int i = 0; i <= live.size() - max; i++) {
            var old = live.get(i);
            accountSessions.revokeFamily(session.account().id(), old.familyId(), now);
            revoked(session.account().id(), old.familyId(), old.clubId(), "LIMIT");
        }
        Tokens tokens = issue(session, clientId, UUID.randomUUID().toString(), opaque(), null, device(userAgent), now, now);
        accounts.recordLogin(session.account().id(), clientId, now);
        if (session.membership() != null) { memberships.accessed(session.account().id(), now); }
        return tokens;
    }
    public Tokens refresh(String value, String clientId) {
        Outcome outcome = transactions.run(() -> {
            Instant now = clock.instant();
            var old = refreshTokens.find(digest(value), clientId).orElseThrow(() -> new ApiException(ErrorCode.REFRESH_EXPIRED));
            accounts.touchSessions(old.accountId());
            if (old.replacedByHash() != null) {
                refreshTokens.revokeFamily(old.familyId(), now);
                return new Outcome(null, ErrorCode.REFRESH_REUSED, old.accountId());
            }
            var session = identities.current(old.accountId());
            if (old.revokedAt() != null || !old.expiresAt().isAfter(now)) { throw new ApiException(ErrorCode.REFRESH_EXPIRED); }
            if (session.account().familyVersion() != old.tokenFamilyVersion()) { throw new ApiException(ErrorCode.REFRESH_EXPIRED); }
            String next = opaque();
            if (!refreshTokens.rotate(old.id(), digest(next), now)) {
                refreshTokens.revokeFamily(old.familyId(), now);
                return new Outcome(null, ErrorCode.REFRESH_REUSED, old.accountId());
            }
            return new Outcome(issue(session, clientId, old.familyId(), next, old.activeProfile(), old.deviceLabel(), old.createdAt(), now), null, old.accountId());
        });
        if (outcome.error() != null) {
            securityEvents.record(SecurityEvents.Type.REFRESH_TOKEN_REUSED, outcome.accountId(), TenantContext.current());
            throw new ApiException(outcome.error());
        }
        return outcome.tokens();
    }
    public Jwt profile(String accountId, String clientId, String familyId, Role profile, boolean remember) {
        return transactions.run(() -> {
            var session = identities.current(accountId);
            if (session.membership() == null || !session.membership().roles().contains(profile)) { throw new ApiException(ErrorCode.PROFILE_NOT_AVAILABLE); }
            requireFamily(accountId, clientId, familyId, session.account().familyVersion());
            accounts.touchSessions(accountId);
            memberships.profile(accountId, profile, remember);
            refreshTokens.profile(accountId, familyId, profile);
            return access(session, clientId, familyId, profile, clock.instant());
        });
    }
    public RefreshToken requireFamily(String accountId, String clientId, String familyId, long version) {
        var token = refreshTokens.activeFamily(accountId, familyId, clock.instant())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!token.clientId().equals(clientId) || token.tokenFamilyVersion() != version) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
        return token;
    }
    public List<RefreshToken> sessions(String accountId) {
        var session = identities.current(accountId);
        return TenantContext.current() == null ? accountSessions.active(accountId, session.account().familyVersion(), clock.instant())
                : refreshTokens.sessions(accountId, clock.instant(), session.account().familyVersion());
    }
    public void revoke(String accountId, String value) {
        transactions.run(() -> {
            identities.current(accountId);
            refreshTokens.ownedToken(accountId, digest(value)).ifPresent(token -> {
                refreshTokens.revokeFamily(token.familyId(), clock.instant());
                if (token.revokedAt() == null) { revoked(accountId, token.familyId(), token.clubId(), "LOGOUT"); }
            });
            return null;
        });
    }
    public void revokeSession(String accountId, String familyId) {
        transactions.run(() -> {
            var owned = sessions(accountId).stream().filter(token -> token.familyId().equals(familyId)).findFirst();
            owned.ifPresent(token -> {
                accounts.touchSessions(accountId);
                accountSessions.revokeFamily(accountId, familyId, clock.instant());
                revoked(accountId, familyId, token.clubId(), "LOGOUT");
            });
            return null;
        });
    }
    public void revokeAll(String accountId) {
        transactions.run(() -> {
            accounts.revokeAll(accountId, clock.instant());
            accountSessions.revokeAll(accountId, clock.instant());
            return null;
        });
    }
    private void revoked(String accountId, String familyId, String clubId, String reason) {
        events.publish(new IdentityEvent(IdentityEvent.Kind.SessionRevoked, clubId, accountId, clock.instant(),
                Map.of("accountId", accountId, "familyId", familyId, "reason", reason)));
    }
    private Tokens issue(IdentityService.Session session, String clientId, String familyId, String refresh, Role requested,
                         String device, Instant createdAt, Instant now) {
        Role profile = session.membership() == null ? null : session.membership().activeProfile(requested);
        Jwt jwt = access(session, clientId, familyId, profile, now);
        Instant expiry = now.plus(Duration.ofDays(settings.integer("auth.sessionDays")));
        refreshTokens.extendFamilyRetention(familyId, expiry);
        refreshTokens.insert(new RefreshToken(UUID.randomUUID().toString(), digest(refresh), session.account().id(), TenantContext.current(),
                clientId, familyId, session.account().familyVersion(), createdAt, expiry, now, null, null, profile, device, RefreshToken.Status.ACTIVE));
        return new Tokens(jwt, new OAuth2RefreshToken(refresh, now, expiry));
    }
    private Jwt access(IdentityService.Session session, String clientId, String familyId, Role profile, Instant now) {
        var account = session.account();
        var membership = session.membership();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(account.id()).audience(List.of(clientId))
                .issuedAt(now).expiresAt(now.plus(ACCESS_TTL)).id(UUID.randomUUID().toString()).claim("sid", familyId)
                .claim("azp", clientId).claim("email", account.email()).claim("name", account.name())
                .claim("locale", account.locale()).claim("platformRoles", account.platformRoles().stream().map(Enum::name).sorted().toList());
        if (membership != null) {
            claims.claim("clubId", membership.clubId()).claim("roles", membership.roles().stream().map(Enum::name).sorted().toList())
                    .claim("activeProfile", profile.name());
            if (membership.memberId() != null) { claims.claim("memberId", membership.memberId()); }
            if (membership.instructorId() != null) { claims.claim("instructorId", membership.instructorId()); }
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()));
    }
    public static String device(String agent) {
        if (agent == null || agent.isBlank()) { return "Unknown device"; }
        // Use a fixed summary, never persist raw client text that could contain credentials.
        String browser = agent.contains("Firefox/") ? "Firefox" : agent.contains("Edg/") ? "Edge"
                : agent.contains("Chrome/") ? "Chrome" : agent.contains("Safari/") ? "Safari" : "Other browser";
        String os = agent.contains("Android") ? "Android" : agent.contains("iPhone") || agent.contains("iPad") ? "iOS"
                : agent.contains("Windows") ? "Windows" : agent.contains("Macintosh") ? "macOS" : agent.contains("Linux") ? "Linux" : "Other OS";
        return browser + " / " + os;
    }
    public static String opaque() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String digest(String value) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public record Tokens(Jwt access, OAuth2RefreshToken refresh) {
        @Override public String toString() { return "Tokens[redacted]"; }
    }
    private record Outcome(Tokens tokens, ErrorCode error, String accountId) { }
}

package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.RefreshToken;
import com.agilityhub.core.identity.persistence.RefreshTokenRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
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
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TokenService {
    public static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private final IdentityService identities;
    private final AccountRepository accounts;
    private final RefreshTokenRepository refreshTokens;
    private final ClubConfigService clubs;
    private final JwtEncoder encoder;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String issuer;
    private final com.agilityhub.core.shared.application.SecurityEvents events;
    private final SecureRandom random = new SecureRandom();

    public TokenService(IdentityService identities, AccountRepository accounts, RefreshTokenRepository refreshTokens,
                        ClubConfigService clubs, JwtEncoder encoder, Clock clock, org.springframework.data.mongodb.MongoTransactionManager transactions,
                        @Value("${identity.issuer}") String issuer, com.agilityhub.core.shared.application.SecurityEvents events) {
        this.identities = identities; this.accounts = accounts; this.refreshTokens = refreshTokens;
        this.clubs = clubs; this.encoder = encoder; this.clock = clock; this.transactions = new TransactionTemplate(transactions); this.issuer = issuer;
        this.events = events;
    }
    public Tokens password(String email, String password, String clientId) {
        var session = identities.authenticate(email, password);
        return transactions.execute(status -> {
            Instant now = clock.instant();
            Instant expires = now.plus(Duration.ofDays(clubs.get(TenantContext.require()).get("auth.sessionDays", Integer.class)));
            Tokens tokens = issue(session, clientId, UUID.randomUUID().toString(), opaque(), now, expires);
            accounts.recordLogin(session.account().id(), clientId, now);
            return tokens;
        });
    }
    public Tokens refresh(String value, String clientId) {
        // Reuse revocation must commit before returning the catalog error to the caller.
        Outcome outcome = transactions.execute(status -> {
            Instant now = clock.instant();
            var old = refreshTokens.find(digest(value), clientId).orElseThrow(() -> new ApiException(ErrorCode.REFRESH_EXPIRED));
            if (old.replacedByHash() != null) {
                refreshTokens.revokeFamily(old.familyId(), now);
                return new Outcome(null, ErrorCode.REFRESH_REUSED, old.accountId());
            }
            if (old.revokedAt() != null || !old.expiresAt().isAfter(now)) { throw new ApiException(ErrorCode.REFRESH_EXPIRED); }
            var session = identities.current(old.accountId());
            if (version(session) != old.tokenFamilyVersion()) { throw new ApiException(ErrorCode.REFRESH_EXPIRED); }
            String next = opaque();
            if (!refreshTokens.rotate(old.id(), digest(next), now)) { throw new ApiException(ErrorCode.REFRESH_REUSED); }
            return new Outcome(issue(session, clientId, old.familyId(), next, now, old.expiresAt()), null, old.accountId());
        });
        if (outcome.error() != null) {
            events.record(com.agilityhub.core.shared.application.SecurityEvents.Type.REFRESH_TOKEN_REUSED,
                    outcome.accountId(), TenantContext.require());
            throw new ApiException(outcome.error());
        }
        return outcome.tokens();
    }
    private Tokens issue(IdentityService.Session session, String clientId, String familyId, String refresh,
                         Instant now, Instant expires) {
        var account = session.account();
        var membership = session.membership();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(account.id()).audience(List.of(clientId))
                .issuedAt(now).expiresAt(now.plus(ACCESS_TTL)).id(UUID.randomUUID().toString())
                .claim("azp", clientId).claim("email", account.email()).claim("name", account.name())
                .claim("locale", account.locale()).claim("platformRoles", account.platformRoles().stream().map(Enum::name).sorted().toList())
                .claim("clubId", TenantContext.require()).claim("roles", membership.roles().stream().map(Enum::name).sorted().toList())
                .claim("activeProfile", membership.defaultProfile() == null
                        ? membership.roles().stream().sorted().findFirst().orElseThrow().name() : membership.defaultProfile().name());
        if (membership.memberId() != null) { claims.claim("memberId", membership.memberId()); }
        Jwt jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()));
        refreshTokens.insert(new RefreshToken(UUID.randomUUID().toString(), digest(refresh), account.id(), TenantContext.require(),
                clientId, familyId, version(session), now, expires, null, null, null));
        return new Tokens(jwt, new OAuth2RefreshToken(refresh, now, expires));
    }
    private long version(IdentityService.Session session) {
        return session.account().security() == null ? 0 : session.account().security().tokenFamilyVersion();
    }
    private String opaque() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
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

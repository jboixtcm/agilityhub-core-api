package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.HandoffCode;
import com.agilityhub.core.identity.persistence.HandoffCodeRepository;
import com.agilityhub.core.identity.persistence.RefreshToken;
import com.agilityhub.core.platform.application.ClubAppUrls;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class HandoffService {
    public static final String GRANT = "urn:agilityhub:grant:handoff";
    /** S01 R-01-13: a handoff code lives 60 s, the app-switch code and the impersonation launch code alike (R-01-09). */
    public static final long CODE_SECONDS = 60;
    private final HandoffCodeRepository codes;
    private final IdentityService identities;
    private final TokenService tokens;
    private final IdentityTransactions transactions;
    private final ClubAppUrls urls;
    private final SecurityEvents security;
    private final Clock clock;
    private final ImpersonationService impersonations;
    public HandoffService(HandoffCodeRepository codes, IdentityService identities, TokenService tokens,
            IdentityTransactions transactions, ClubAppUrls urls, SecurityEvents security, Clock clock, ImpersonationService impersonations) {
        this.codes = codes; this.identities = identities; this.tokens = tokens; this.transactions = transactions;
        this.urls = urls; this.security = security; this.clock = clock; this.impersonations = impersonations;
    }
    public Created create(String accountId, String sourceClientId, String familyId, String targetClientId) {
        // FE-03 (pre-production audit): R-01-13 switches between the two apps; no client asks the club app for a code into itself.
        if ("clubs-app".equals(sourceClientId) && "clubs-app".equals(targetClientId)) { throw new ApiException(ErrorCode.NO_MEMBERSHIP); }
        return transactions.run(() -> {
            var session = eligible(accountId, targetClientId);
            if (!("clubs-app".equals(sourceClientId) || "clubs-admin".equals(sourceClientId))) { throw new ApiException(ErrorCode.NO_MEMBERSHIP); }
            tokens.requireFamily(accountId, sourceClientId, familyId, session.account().familyVersion());
            String url = urls.login(targetClientId);
            String code = TokenService.opaque();
            codes.insert(new HandoffCode(UUID.randomUUID().toString(), TenantContext.require(), accountId, targetClientId,
                    TokenService.digest(code), sourceClientId, familyId, session.account().familyVersion(), clock.instant().plusSeconds(CODE_SECONDS), null));
            return new Created(code, url + "?handoff=" + code);
        });
    }
    public TokenService.Tokens exchange(String value, String clientId, String userAgent) { return exchange(value, clientId, userAgent, false); }
    public TokenService.Tokens exchange(String value, String clientId, String userAgent, boolean consoleHost) {
        return exchange(value, clientId, userAgent, consoleHost, null);
    }
    /**
     * @param consoleHost S01 R-01-02 (E157): redeemed on the console's product host, the code opens a platform session
     * @param hostRefresh FE-03 (S01 R-01-13): the request's `ah_refresh` cookie of this host, or null. When it holds a live session
     *     of this client of an account other than the code's, an account's code (never an impersonation launch code) is refused
     *     with `409 HANDOFF_SESSION_CONFLICT` before it is consumed: the code stays unused and no tokens are issued.
     */
    public TokenService.Tokens exchange(String value, String clientId, String userAgent, boolean consoleHost, String hostRefresh) {
        var code = codes.resolve(TokenService.digest(value), clientId).orElse(null);
        if (code == null || (TenantContext.current() != null && !TenantContext.current().equals(code.clubId()))) {
            return invalid(null, TenantContext.current());
        }
        // FE-03: whose live session the host's cookie holds, read in the host's own context (its club, or none on a product host).
        String hostAccount = ImpersonationService.HANDOFF_SOURCE.equals(code.sourceClientId()) ? null : tokens.liveSessionAccount(hostRefresh, clientId);
        if (consoleHost) { return console(code, clientId, userAgent, hostAccount); }
        try (var scope = TenantContext.open(code.clubId())) {
            var result = transactions.run(() -> {
                if (code.usedAt() != null || !code.expiresAt().isAfter(clock.instant())) { return null; }
                // S01 R-01-09 (E5-T27, ruling E47): the impersonation launch code opens the grant's own JWT, without refresh.
                if (ImpersonationService.HANDOFF_SOURCE.equals(code.sourceClientId())) {
                    return impersonations.redeem(code).map(jwt -> new TokenService.Tokens(jwt, null)).orElse(null);
                }
                var session = eligible(code.accountId(), clientId);
                if (session.account().familyVersion() != code.tokenFamilyVersion()) { return null; }
                RefreshToken source;
                try { source = tokens.requireFamily(code.accountId(), code.sourceClientId(), code.sourceFamilyId(), code.tokenFamilyVersion()); }
                catch (ApiException revoked) { return null; }
                urls.login(clientId);
                refuseAnotherSession(hostAccount, code);
                if (!codes.consume(code.id(), clock.instant())) { return null; }
                return tokens.handoff(session, clientId, userAgent, signedIn(source));
            });
            return result == null ? invalid(code.accountId(), code.clubId()) : result;
        }
    }
    /**
     * S01 R-01-02, S17 §2 «Entrada» (ruling E157): on the console's product host a `clubs-admin` code is checked in its own club (alive,
     * unused, its source session live) and opens a session without a club, which only an `AGILITYHUB_ADMIN` gets (`403 PLATFORM_ONLY`,
     * the code left unused). An impersonation launch code never opens there: it belongs to the club's app.
     */
    private TokenService.Tokens console(HandoffCode code, String clientId, String userAgent, String hostAccount) {
        if (!TokenService.CONSOLE_CLIENT.equals(clientId) || ImpersonationService.HANDOFF_SOURCE.equals(code.sourceClientId())) {
            return invalid(code.accountId(), code.clubId());
        }
        var result = transactions.run(() -> {
            var session = identities.current(code.accountId());
            RefreshToken source;
            try (var scope = TenantContext.open(code.clubId())) {
                if (code.usedAt() != null || !code.expiresAt().isAfter(clock.instant())
                        || session.account().familyVersion() != code.tokenFamilyVersion()) { return null; }
                try { source = tokens.requireFamily(code.accountId(), code.sourceClientId(), code.sourceFamilyId(), code.tokenFamilyVersion()); }
                catch (ApiException revoked) { return null; }
                refuseAnotherSession(hostAccount, code);
                if (!codes.consume(code.id(), clock.instant())) { return null; }
            }
            return tokens.handoff(session, clientId, userAgent, signedIn(source));
        });
        return result == null ? invalid(code.accountId(), code.clubId()) : result;
    }
    /**
     * FE-03 (S01 R-01-13, owner decision of 13 Nov 2026): the host already holds another account's live session, which the code would
     * replace. The answer names neither account; thrown inside the redemption's transaction, it leaves the code unused.
     */
    private static void refuseAnotherSession(String hostAccount, HandoffCode code) {
        if (hostAccount != null && !hostAccount.equals(code.accountId())) { throw new ApiException(ErrorCode.HANDOFF_SESSION_CONFLICT); }
    }
    /** AUTH-01 (S01 R-01-18): the sign-in time of the source session, which the new one keeps (its creation for an older row). */
    static java.time.Instant signedIn(RefreshToken source) { return source.authTime() != null ? source.authTime() : source.createdAt(); }
    private IdentityService.Session eligible(String accountId, String target) {
        var session = identities.current(accountId);
        if (session.membership() == null || !("clubs-admin".equals(target) ? session.membership().roles().contains(Role.ADMIN)
                : "clubs-app".equals(target))) { throw new ApiException(ErrorCode.NO_MEMBERSHIP); }
        return session;
    }
    private TokenService.Tokens invalid(String accountId, String clubId) {
        security.record(SecurityEvents.Type.HANDOFF_INVALID, accountId, clubId);
        throw new ApiException(ErrorCode.HANDOFF_INVALID);
    }
    public record Created(String code, String url) {
        @Override public String toString() { return "Created[redacted]"; }
    }
}

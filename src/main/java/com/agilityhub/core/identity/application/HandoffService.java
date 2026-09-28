package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.HandoffCode;
import com.agilityhub.core.identity.persistence.HandoffCodeRepository;
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
    public TokenService.Tokens exchange(String value, String clientId, String userAgent) {
        var code = codes.resolve(TokenService.digest(value), clientId).orElse(null);
        if (code == null || (TenantContext.current() != null && !TenantContext.current().equals(code.clubId()))) {
            return invalid(null, TenantContext.current());
        }
        try (var scope = TenantContext.open(code.clubId())) {
            var result = transactions.run(() -> {
                if (code.usedAt() != null || !code.expiresAt().isAfter(clock.instant())) { return null; }
                // S01 R-01-09 (E5-T27, ruling E47): the impersonation launch code opens the grant's own JWT, without refresh.
                if (ImpersonationService.HANDOFF_SOURCE.equals(code.sourceClientId())) {
                    return impersonations.redeem(code).map(jwt -> new TokenService.Tokens(jwt, null)).orElse(null);
                }
                var session = eligible(code.accountId(), clientId);
                if (session.account().familyVersion() != code.tokenFamilyVersion()) { return null; }
                try { tokens.requireFamily(code.accountId(), code.sourceClientId(), code.sourceFamilyId(), code.tokenFamilyVersion()); }
                catch (ApiException revoked) { return null; }
                urls.login(clientId);
                if (!codes.consume(code.id(), clock.instant())) { return null; }
                return tokens.handoff(session, clientId, userAgent);
            });
            return result == null ? invalid(code.accountId(), code.clubId()) : result;
        }
    }
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

package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.SupportAccessStarted;
import com.agilityhub.core.identity.persistence.Account;
import com.agilityhub.core.identity.persistence.AccountRepository;
import com.agilityhub.core.identity.persistence.ImpersonationGrant;
import com.agilityhub.core.identity.persistence.ImpersonationGrantRepository;
import com.agilityhub.core.platform.application.ConsoleSupportAccess;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S17 R-17-10 (E10-T04): the AgilityHub team's support access to a club. A `SUPPORT` grant ({@link ImpersonationGrant}) of
 * `platform.supportAccessMinutes` issues a `clubs-admin` token with the club, `roles: [ADMIN]`, `actorAccountId` (the platform
 * account, also its subject), `support: true` and no platform role and no refresh, so it opens the club's administration and
 * nothing of the console. Every request re-checks the grant ({@link #validate}): ended (`/oauth2/revoke`), expired, or its actor
 * no longer an active AgilityHub administrator → `401`. Personal-data exports and full bank accounts stay closed to it
 * (`403 SUPPORT_RESTRICTED`, {@link #restricted}). The start is audited `SUPPORT_ACCESS_STARTED` in the club's own audit, so the
 * club sees it, and every action taken with the token is audited with `origin = PLATFORM_SUPPORT`.
 */
@Service
public class SupportAccessService implements ConsoleSupportAccess {
    public static final String CLIENT = "clubs-admin";
    /**
     * `METHOD path` of the routes a support token never reaches: every personal-data export and its files (S14), the SEPA files,
     * which carry full IBANs (S12), the club's member impersonation (S01 R-01-09) and the platform account's own credentials and
     * sessions. The members' and the club's IBANs are masked everywhere else already (R-03-27, R-17-05).
     */
    static final Pattern RESTRICTED = Pattern.compile("(GET|POST) /api/v1/[a-z-]+/export|(GET|POST) /api/v1/instructor/week/export"
            + "|GET /api/v1/exports(/.*)?|GET /api/v1/billing/exports|POST /api/v1/(members/[^/]+|me)/data-export"
            + "|GET /api/v1/remittances/[^/]+/file|GET /api/v1/remittances/files/.+"
            + "|POST /api/v1/members/[^/]+/impersonation-token"
            + "|(PUT|PATCH|DELETE|POST) /api/v1/me(/password|/sessions(/[^/]+)?|/onboarding(/postpone)?)?");
    private final ImpersonationGrantRepository grants; private final AccountRepository accounts; private final AuthSettings settings;
    private final EventPublisher events; private final JwtEncoder encoder; private final Clock clock; private final String issuer;
    public SupportAccessService(ImpersonationGrantRepository grants, AccountRepository accounts, AuthSettings settings, EventPublisher events,
            JwtEncoder encoder, Clock clock, @Value("${identity.issuer}") String issuer) {
        this.grants = grants; this.accounts = accounts; this.settings = settings; this.events = events; this.encoder = encoder;
        this.clock = clock; this.issuer = issuer;
    }

    @Override
    @Transactional
    @Audited(action = AuditAction.SUPPORT_ACCESS_STARTED, entityType = "'ImpersonationGrant'", entity = "#result.grantId", reason = "#reason",
            details = "T(java.util.Map).of('expiresAt', #result.expiresAt.toString())")
    public Issued start(String reason) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var actor = accounts.findById(authentication.getName()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        if (!platformAdmin(actor)) { throw new ApiException(ErrorCode.FORBIDDEN); }
        Instant now = clock.instant();
        var grant = grants.insert(new ImpersonationGrant(UUID.randomUUID().toString(), TenantContext.require(), actor.id(), null, null, reason,
                now.plusSeconds(settings.integer("platform.supportAccessMinutes") * 60L), null, ImpersonationGrant.Type.SUPPORT, now));
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(actor.id()).audience(List.of(CLIENT))
                .issuedAt(now).expiresAt(grant.expiresAt()).id(grant.id()).claim("azp", CLIENT)
                .claim("clubId", grant.clubId()).claim("roles", List.of("ADMIN")).claim("activeProfile", "ADMIN")
                .claim("actorAccountId", actor.id()).claim("support", true)
                .claim("name", actor.name()).claim("email", actor.email()).claim("locale", actor.locale());
        Jwt jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()));
        events.publish(new SupportAccessStarted(grant.clubId(), grant.id(), now, actor.id()));
        return new Issued(grant.id(), jwt.getTokenValue(), grant.expiresAt());
    }

    /**
     * Every request of a support token: its grant (in the token's club) is a live `SUPPORT` grant of the token's subject, and
     * that account is still an active AgilityHub administrator. `revoking` lets `/oauth2/revoke` end a grant already ended.
     */
    public CurrentUser.Support validate(Jwt jwt, boolean revoking) {
        // A global route (the console's /platform/*, an account route without a club) is no club administration.
        if (TenantContext.current() == null) { throw new ApiException(ErrorCode.FORBIDDEN); }
        var grant = grants.findById(jwt.getId()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!grant.support() || (!revoking && grant.revokedAt() != null) || !grant.expiresAt().isAfter(clock.instant())
                || !grant.actorAccountId().equals(jwt.getSubject()) || !grant.actorAccountId().equals(jwt.getClaimAsString("actorAccountId"))
                || !grant.clubId().equals(jwt.getClaimAsString("clubId"))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        var actor = accounts.findById(grant.actorAccountId()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if (!platformAdmin(actor)) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
        return new CurrentUser.Support(grant.id(), actor.id(), actor.name(), grant.expiresAt());
    }

    /** The support access a club ended: its grant and the platform account it belonged to. */
    public record Ended(String grantId, String actorAccountId) { }

    /**
     * R-17-10 «revocable» (E10-T04 round 2, E123): the club's ADMIN ends a live support access of its club. The grant's token
     * stops at its next request ({@link #validate}). Another club's grant, a member impersonation or an unknown id is
     * `404 NOT_FOUND`; an access already ended or expired has nothing to end (`409 INVALID_STATE {status: ENDED | EXPIRED}`).
     */
    @Transactional
    @Audited(action = AuditAction.SUPPORT_ACCESS_ENDED, entityType = "'ImpersonationGrant'", entity = "#result.grantId",
            details = "T(java.util.Map).of('actorAccountId', #result.actorAccountId)")
    public Ended end(String grantId) {
        var grant = grants.findById(grantId).filter(ImpersonationGrant::support).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Instant now = clock.instant();
        if (grant.revokedAt() != null) { throw new ApiException(ErrorCode.INVALID_STATE, java.util.Map.of("status", "ENDED")); }
        if (!grant.expiresAt().isAfter(now)) { throw new ApiException(ErrorCode.INVALID_STATE, java.util.Map.of("status", "EXPIRED")); }
        // A revocation that lands first (the actor's «Surt») leaves nothing to end either.
        if (!grants.revoke(grant.id(), now)) { throw new ApiException(ErrorCode.INVALID_STATE, java.util.Map.of("status", "ENDED")); }
        events.publish(new com.agilityhub.core.identity.domain.SupportAccessEnded(grant.clubId(), grant.id(), now, grant.actorAccountId()));
        return new Ended(grant.id(), grant.actorAccountId());
    }

    /**
     * Whether `method path` is closed to a support token ({@link #RESTRICTED}). Spring MVC also routes `HEAD` to a `GET` handler,
     * so a `HEAD` is closed as its `GET` (INC-65).
     */
    public static boolean restricted(String method, String path) {
        return RESTRICTED.matcher(("HEAD".equals(method) ? "GET" : method) + " " + path).matches();
    }

    private static boolean platformAdmin(Account account) {
        return account.status() == Account.Status.ACTIVE && account.platformRoles().contains(Account.PlatformRole.AGILITYHUB_ADMIN);
    }
}

package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.ImpersonationEvent;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.Audited;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImpersonationService {
    private final ImpersonationGrantRepository grants;
    private final IdentityService identities;
    private final MemberIdentityAccess members;
    private final AuthSettings settings;
    private final EventPublisher events;
    private final SecurityEvents security;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;
    private final String issuer;
    public ImpersonationService(ImpersonationGrantRepository grants, IdentityService identities, MemberIdentityAccess members,
            AuthSettings settings, EventPublisher events, SecurityEvents security, JwtEncoder encoder, JwtDecoder decoder,
            Clock clock, @Value("${identity.issuer}") String issuer) {
        this.grants = grants; this.identities = identities; this.members = members; this.settings = settings;
        this.events = events; this.security = security; this.encoder = encoder; this.decoder = decoder; this.clock = clock; this.issuer = issuer;
    }
    public void rejected(String accountId) { security.record(SecurityEvents.Type.IMPERSONATION_DENIED, accountId, TenantContext.current()); }
    public void deny(String accountId) {
        rejected(accountId);
        throw new ApiException(ErrorCode.IMPERSONATION_DENIED);
    }
    @Transactional
    @Audited(action = AuditAction.IMPERSONATION_STARTED, entityType = "'ImpersonationGrant'", member = "#memberId", reason = "#reason")
    public Issued create(String actorAccountId, String memberId, String reason) {
        var actor = identities.current(actorAccountId);
        if (actor.membership() == null || !actor.membership().roles().contains(Role.ADMIN)) { throw new ApiException(ErrorCode.IMPERSONATION_DENIED); }
        var target = identities.current(members.impersonationAccount(memberId));
        if (!memberId.equals(target.membership().memberId()) || !target.membership().roles().contains(Role.MEMBER)) { throw new ApiException(ErrorCode.IMPERSONATION_DENIED); }
        Instant now = clock.instant();
        var grant = grants.insert(new ImpersonationGrant(UUID.randomUUID().toString(), TenantContext.require(), actorAccountId,
                memberId, target.account().id(), reason, now.plusSeconds(settings.integer("auth.impersonationMinutes") * 60L), null));
        var account = target.account();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(account.id()).audience(List.of("clubs-app"))
                .issuedAt(now).expiresAt(grant.expiresAt()).id(grant.id()).claim("azp", "clubs-app")
                .claim("clubId", grant.clubId()).claim("roles", List.of("MEMBER")).claim("activeProfile", "MEMBER")
                .claim("memberId", memberId).claim("imp", true).claim("actorAccountId", actorAccountId)
                .claim("impersonatedMemberId", memberId).claim("name", account.name()).claim("email", account.email()).claim("locale", account.locale());
        var jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()));
        publish(ImpersonationEvent.Kind.ImpersonationStarted, grant);
        return new Issued(grant.id(), grant.expiresAt(), jwt);
    }
    public CurrentUser.Impersonation validate(Jwt jwt, boolean revoking) {
        if (TenantContext.current() == null) { throw new ApiException(ErrorCode.FORBIDDEN); }
        var grant = grants.findById(jwt.getId()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        if ((!revoking && grant.revokedAt() != null) || !grant.expiresAt().isAfter(clock.instant())
                || !grant.impersonatedAccountId().equals(jwt.getSubject())
                || !grant.actorAccountId().equals(jwt.getClaimAsString("actorAccountId"))
                || !grant.impersonatedMemberId().equals(jwt.getClaimAsString("impersonatedMemberId"))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        var actor = identities.current(grant.actorAccountId());
        var target = identities.current(grant.impersonatedAccountId());
        if (!actor.membership().roles().contains(Role.ADMIN) || !target.membership().roles().contains(Role.MEMBER)
                || !Objects.equals(target.membership().memberId(), grant.impersonatedMemberId())
                || !grant.impersonatedAccountId().equals(members.impersonationAccount(grant.impersonatedMemberId()))) {
            throw new ApiException(ErrorCode.IMPERSONATION_DENIED);
        }
        return new CurrentUser.Impersonation(grant.actorAccountId(), actor.account().name(), grant.impersonatedMemberId());
    }
    @Transactional
    public boolean revoke(String requester, String value) {
        // Opaque refresh credentials never have a JWT separator.
        if (!value.contains(".")) { return false; }
        Jwt jwt;
        try { jwt = decoder.decode(value); } catch (JwtException invalid) { return true; }
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("imp")) || TenantContext.current() == null) { return true; }
        var grant = grants.findById(jwt.getId()).orElse(null);
        if (grant != null && (requester.equals(grant.actorAccountId()) || requester.equals(grant.impersonatedAccountId()))
                && grants.revoke(grant.id(), clock.instant())) { publish(ImpersonationEvent.Kind.ImpersonationEnded, grant); }
        return true;
    }
    private void publish(ImpersonationEvent.Kind kind, ImpersonationGrant grant) {
        events.publish(new ImpersonationEvent(kind, grant.clubId(), grant.id(), clock.instant(), grant.actorAccountId(), grant.impersonatedMemberId()));
    }
    public record Issued(String id, @AuditField Instant expiresAt, Jwt token) {
        @Override public String toString() { return "Issued[redacted]"; }
    }
}

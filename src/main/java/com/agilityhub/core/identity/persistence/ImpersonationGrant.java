package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S01 `impersonation_grants`. `type` (S17 R-17-10, E10-T04): `IMPERSONATION` (S01 R-01-09, an admin opening a member's app;
 * null on a grant stored before E10-T04) or `SUPPORT` (the AgilityHub team entering a club as its administrator: no
 * impersonated member, `actorAccountId` is the platform account and the grant's own subject). INC-37 (E11-T09): `createdAt`, when
 * its first JWT was signed (null on a grant stored before, read as «before any rotation»), and `endReason`, why an
 * impersonation ended (`LOGOUT` or `EXPIRED`, S01 §7 `ImpersonationEnded{reason}`), set with `revokedAt` in the same write.
 */
@Document("impersonation_grants")
public record ImpersonationGrant(@Id String id, String clubId, String actorAccountId,
        String impersonatedMemberId, String impersonatedAccountId, String reason,
        Instant expiresAt, Instant revokedAt, Type type, Instant createdAt, EndReason endReason) implements TenantEntity {
    public enum Type { IMPERSONATION, SUPPORT }
    /** S01 §7: `ImpersonationEnded{grantId, reason: LOGOUT·EXPIRED}`. */
    public enum EndReason { LOGOUT, EXPIRED }
    public ImpersonationGrant(String id, String clubId, String actorAccountId, String impersonatedMemberId, String impersonatedAccountId,
            String reason, Instant expiresAt, Instant revokedAt, Type type, Instant createdAt) {
        this(id, clubId, actorAccountId, impersonatedMemberId, impersonatedAccountId, reason, expiresAt, revokedAt, type, createdAt, null);
    }
    public ImpersonationGrant(String id, String clubId, String actorAccountId, String impersonatedMemberId, String impersonatedAccountId,
            String reason, Instant expiresAt, Instant revokedAt, Type type) {
        this(id, clubId, actorAccountId, impersonatedMemberId, impersonatedAccountId, reason, expiresAt, revokedAt, type, null, null);
    }
    public ImpersonationGrant(String id, String clubId, String actorAccountId, String impersonatedMemberId, String impersonatedAccountId,
            String reason, Instant expiresAt, Instant revokedAt) {
        this(id, clubId, actorAccountId, impersonatedMemberId, impersonatedAccountId, reason, expiresAt, revokedAt, Type.IMPERSONATION);
    }
    public boolean support() { return type == Type.SUPPORT; }
}

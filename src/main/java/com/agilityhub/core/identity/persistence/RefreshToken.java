package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.Sensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("refresh_tokens")
public record RefreshToken(@Id String id, @JsonIgnore @Sensitive(Sensitive.Strategy.HIDE) String tokenHash, String accountId, String clubId,
                           String clientId, String familyId, long tokenFamilyVersion,
                           Instant createdAt, Instant expiresAt, Instant lastUsedAt,
                           Instant revokedAt, @JsonIgnore @Sensitive(Sensitive.Strategy.HIDE) String replacedByHash,
                           Role activeProfile, String deviceLabel, Status status, java.util.Set<String> scopes, boolean oidc, String nonce, Instant authTime) implements TenantEntity {
    public RefreshToken(String id, String tokenHash, String accountId, String clubId, String clientId, String familyId,
                        long tokenFamilyVersion, Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt, String replacedByHash,
                        Role activeProfile, String deviceLabel, Status status) {
        this(id, tokenHash, accountId, clubId, clientId, familyId, tokenFamilyVersion, createdAt, expiresAt, lastUsedAt,
                revokedAt, replacedByHash, activeProfile, deviceLabel, status, java.util.Set.of(), false, null, createdAt);
    }
    public RefreshToken(String id, String tokenHash, String accountId, String clubId, String clientId, String familyId,
                        long tokenFamilyVersion, Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt, String replacedByHash) {
        this(id, tokenHash, accountId, clubId, clientId, familyId, tokenFamilyVersion, createdAt, expiresAt, lastUsedAt,
                revokedAt, replacedByHash, null, "Unknown device", null);
    }
    public RefreshToken {
        if (scopes == null) { scopes = java.util.Set.of(); }
        if (authTime == null) { authTime = createdAt; }
        if (status == null) { status = revokedAt != null ? Status.REVOKED : replacedByHash != null ? Status.ROTATED : Status.ACTIVE; }
        if (deviceLabel == null) { deviceLabel = "Unknown device"; }
    }
    @Override public String toString() { return "RefreshToken[redacted]"; }
    public enum Status { ACTIVE, ROTATED, REVOKED }
}

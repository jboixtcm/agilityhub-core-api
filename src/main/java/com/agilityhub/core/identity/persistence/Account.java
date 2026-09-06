package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.identity.domain.Email;
import com.agilityhub.core.shared.domain.audit.Sensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Internal persistence model. API views explicitly select public fields. */
@Document("accounts")
public record Account(@Id String id, String email, String name, String locale,
                      @Sensitive(Sensitive.Strategy.HIDE) @JsonIgnore String passwordHash, Set<PlatformRole> platformRoles, Status status,
                      @Sensitive(Sensitive.Strategy.HIDE) @JsonIgnore Security security, Map<String, String> externalIds,
                      boolean onboardingPending, Instant createdAt) {
    public Account {
        email = Email.normalize(email);
        platformRoles = Set.copyOf(platformRoles);
        externalIds = Map.copyOf(externalIds);
    }
    @Override public String toString() { return "Account[id=" + id + "]"; }
    public enum PlatformRole { AGILITYHUB_ADMIN }
    public enum Status { ACTIVE, BLOCKED, MERGED, ERASED }
    public record Security(int failedLogins, Instant lockedUntil, Instant passwordChangedAt, long tokenFamilyVersion) { }
}

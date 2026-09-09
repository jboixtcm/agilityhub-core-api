package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("memberships")
public record Membership(@Id String id, String accountId, String clubId, String memberId,
                         Set<Role> roles, Status status, Role defaultProfile, boolean rememberProfile,
                         String instructorId, Instant createdAt, Instant lastAccessAt,
                         AdminProfile adminProfile, long version, Instant updatedAt,
                         String createdByAccountId, String updatedByAccountId) implements TenantEntity {
    public record AdminProfile(String shortName, java.time.LocalDate since, boolean active) { }
    public Membership(String id, String accountId, String clubId, String memberId, Set<Role> roles, Status status,
                      Role defaultProfile, boolean rememberProfile, String instructorId, Instant createdAt, Instant lastAccessAt) {
        this(id, accountId, clubId, memberId, roles, status, defaultProfile, rememberProfile, instructorId, createdAt,
                lastAccessAt, null, 0, null, null, null);
    }
    public Membership(String id, String accountId, String clubId, String memberId, Set<Role> roles, Status status, Role defaultProfile) {
        this(id, accountId, clubId, memberId, roles, status, defaultProfile, false, null, null, null);
    }
    public Membership {
        roles = Set.copyOf(roles);
        if ((roles.isEmpty() && status == Status.ACTIVE) || (defaultProfile != null && !roles.contains(defaultProfile))) {
            throw new IllegalArgumentException("Active membership requires roles and an available default profile");
        }
    }
    public Role activeProfile(Role requested) {
        if (requested != null && roles.contains(requested)) { return requested; }
        if (rememberProfile && defaultProfile != null && roles.contains(defaultProfile)) { return defaultProfile; }
        return roles.stream().sorted().findFirst().orElseThrow();
    }
    public enum Status { ACTIVE, SUSPENDED, ERASED }
}

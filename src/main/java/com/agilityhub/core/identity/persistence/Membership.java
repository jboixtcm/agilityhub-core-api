package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("memberships")
public record Membership(@Id String id, String accountId, String clubId, String memberId,
                         Set<Role> roles, Status status, Role defaultProfile) implements TenantEntity {
    public Membership {
        roles = Set.copyOf(roles);
        if (roles.isEmpty() || (defaultProfile != null && !roles.contains(defaultProfile))) {
            throw new IllegalArgumentException("Membership requires roles and an available default profile");
        }
    }
    public enum Status { ACTIVE, SUSPENDED, ERASED }
}

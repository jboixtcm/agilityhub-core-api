package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** S01 projection; onboarding uses partial updates to preserve the full S03 document. */
@Document("members")
public record MemberIdentity(@Id String id, String clubId, String accountId, String status,
                             java.util.List<Phone> phones, java.util.Map<String, Object> consents, String firstName) implements TenantEntity {
    public MemberIdentity(String id, String clubId, String accountId, String status,
                          java.util.List<Phone> phones, java.util.Map<String, Object> consents) {
        this(id, clubId, accountId, status, phones, consents, null);
    }
    public MemberIdentity(String id, String clubId, String accountId, String status) { this(id, clubId, accountId, status, null, null); }
    public record Phone(String prefix, String number, String label) { }
}

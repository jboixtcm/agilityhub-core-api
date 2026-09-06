package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Read projection for S01; full census writes belong to S03. */
@Document("members")
public record MemberIdentity(@Id String id, String clubId, String accountId, String status) implements TenantEntity { }

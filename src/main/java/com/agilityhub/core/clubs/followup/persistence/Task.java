package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.clubs.followup.domain.AuthorRole;
import com.agilityhub.core.clubs.followup.domain.TaskState;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S10 §3 `Task` (TASCA): `memberId` is the dog's owner and follows `DogTransferred`; `deletedAt` is the logical
 * deletion (BR-12, only `includeDeleted` for ADMIN); `attachmentCount` is kept by the attachment writer.
 */
@Document("tasks")
public record Task(@Id String id, String clubId, String dogId, String memberId, String text, TaskState state,
        Actor createdBy, Actor updatedBy, Actor doneBy, Actor deletedBy, Instant createdAt, Instant updatedAt, Instant doneAt, Instant deletedAt,
        int attachmentCount, @Version Long version) implements TenantEntity {
    public record Actor(String accountId, AuthorRole role, String displayName) { }
}

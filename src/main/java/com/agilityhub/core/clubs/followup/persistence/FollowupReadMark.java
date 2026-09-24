package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S10 §3 read marks of one staff account (R-10-13): «Marcar-ho tot com a llegit» sets `readAllAt = now` and empties
 * `readItemIds` (O(1)); a click appends the item id. Unique per `{clubId, accountId}`.
 */
@Document("followup_read_marks")
public record FollowupReadMark(@Id String id, String clubId, String accountId, Instant readAllAt, List<String> readItemIds,
        Instant createdAt, Instant updatedAt) implements TenantEntity { }

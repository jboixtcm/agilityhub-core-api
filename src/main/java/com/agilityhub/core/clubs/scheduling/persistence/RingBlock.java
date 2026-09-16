package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.*;
import java.util.List;
import com.agilityhub.core.clubs.scheduling.domain.*;

@Document("ring_blocks")
public record RingBlock(@Id String id, String clubId,
        String ringId, Instant from, Instant to, RingBlockKind kind, RingBlockReason reason, String note,
        String activityId, RingBlockState state, Instant cancelledAt, String cancelledByAccountId,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId) implements TenantEntity {

}

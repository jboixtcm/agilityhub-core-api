package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.AuditField;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.*;
import java.util.List;
import com.agilityhub.core.clubs.scheduling.domain.*;

@Document("ring_blocks")
public record RingBlock(@Id String id, String clubId,
        @AuditField String ringId, @AuditField Instant from, @AuditField Instant to, @AuditField RingBlockKind kind, @AuditField RingBlockReason reason, @AuditField String note,
        @AuditField String activityId, @AuditField RingBlockState state, @AuditField Instant cancelledAt, @AuditField String cancelledByAccountId,
        @Version Long version, Instant createdAt, String createdByAccountId, Instant updatedAt, String updatedByAccountId) implements TenantEntity {

}

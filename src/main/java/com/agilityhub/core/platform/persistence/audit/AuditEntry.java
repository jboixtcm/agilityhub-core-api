package com.agilityhub.core.platform.persistence.audit;

import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditChange;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("audit_entries")
public record AuditEntry(@Id String id, String clubId, Instant at, String actorAccountId, String actorName,
                         String actorRole, String impersonatedMemberId, Boolean support, AuditAction action,
                         String entityType, String entityId, String memberId, List<AuditChange> changes,
                         String reason, String ip, String userAgent, String traceId) implements TenantEntity {
    public AuditEntry { changes = List.copyOf(changes); }
}

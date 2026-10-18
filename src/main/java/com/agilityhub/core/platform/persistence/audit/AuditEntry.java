package com.agilityhub.core.platform.persistence.audit;

import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditChange;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("audit_entries")
public record AuditEntry(@Id String id, String clubId, Instant at, String actorAccountId, String actorName,
                         String actorRole, String impersonatedMemberId, Boolean support, AuditAction action,
                         String entityType, String entityId, String memberId, List<AuditChange> changes,
                         String reason, String ip, String userAgent, String traceId, String origin, Map<String, Object> details,
                         String entityLabel, List<String> eventIds) implements TenantEntity {
    public AuditEntry(String id,String clubId,Instant at,String actorAccountId,String actorName,String actorRole,
            String impersonatedMemberId,Boolean support,AuditAction action,String entityType,String entityId,String memberId,
            List<AuditChange> changes,String reason,String ip,String userAgent,String traceId) {
        this(id,clubId,at,actorAccountId,actorName,actorRole,impersonatedMemberId,support,action,entityType,entityId,memberId,changes,reason,ip,userAgent,traceId,null);
    }
    public AuditEntry(String id,String clubId,Instant at,String actorAccountId,String actorName,String actorRole,
            String impersonatedMemberId,Boolean support,AuditAction action,String entityType,String entityId,String memberId,
            List<AuditChange> changes,String reason,String ip,String userAgent,String traceId,String origin) {
        this(id,clubId,at,actorAccountId,actorName,actorRole,impersonatedMemberId,support,action,entityType,entityId,memberId,changes,reason,ip,userAgent,traceId,origin,null);
    }
    public AuditEntry(String id,String clubId,Instant at,String actorAccountId,String actorName,String actorRole,
            String impersonatedMemberId,Boolean support,AuditAction action,String entityType,String entityId,String memberId,
            List<AuditChange> changes,String reason,String ip,String userAgent,String traceId,String origin,Map<String, Object> details) {
        this(id,clubId,at,actorAccountId,actorName,actorRole,impersonatedMemberId,support,action,entityType,entityId,memberId,changes,reason,ip,userAgent,traceId,origin,details,null,null);
    }
    /**
     * `details` (S14 §3, optional): extra data of the entry; a `SYSTEM` entry carries the process in `details.job` (E7-T04
     * round 3, ruling E83: the start-up template upgrade). The audit list already projects it. E11-T10 (INC-38): `entityLabel`
     * is the entity's label when written (S14 §3: «Laura Serra Vidal», «bookings.maxCurrentWeek»; the erasure removes it,
     * R-14-15) and `eventIds[]` the outbox events of the same transaction published before it (R-14-10); none is stored empty.
     */
    public AuditEntry {
        changes = List.copyOf(changes);
        details = details == null ? null : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
        eventIds = eventIds == null || eventIds.isEmpty() ? null : List.copyOf(eventIds);
    }
}

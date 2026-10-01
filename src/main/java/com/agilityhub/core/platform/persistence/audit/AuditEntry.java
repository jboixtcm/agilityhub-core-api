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
                         String reason, String ip, String userAgent, String traceId, String origin, Map<String, Object> details) implements TenantEntity {
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
    /**
     * `details` (S14 §3, optional): extra data of the entry; a `SYSTEM` entry carries the process in `details.job` (E7-T04
     * round 3, ruling E83: the start-up template upgrade). The audit list already projects it.
     */
    public AuditEntry {
        changes = List.copyOf(changes);
        details = details == null ? null : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
    }
}

package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.domain.DataExported;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.application.lists.ListAccess;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExportCompletion {
    private final EventPublisher events;
    private final Clock clock;
    private final AuditWriter audit;
    private final com.agilityhub.core.clubs.messaging.application.engine.NotificationEngine notifications;
    public ExportCompletion(EventPublisher events, Clock clock, AuditWriter audit, com.agilityhub.core.clubs.messaging.application.engine.NotificationEngine notifications) {
        this.events = events; this.clock = clock; this.audit = audit; this.notifications = notifications;
    }

    /**
     * S14 R-14-13: a member's package at `READY` emits `DataExported{kind: MEMBER_DATA}`, audits `MEMBER_DATA_EXPORTED` on the member
     * and, for the member (`/me`, or an admin's `deliverToMember`), triggers N-50 «Les teves dades estan a punt» directly (no catalog
     * event emits it; its action opens 12 `/perfil`).
     */
    public void memberDataCompleted(String id, String memberId, long files, boolean deliver) {
        String by = ListAccess.account(), clubId = TenantContext.require();
        events.publish(new DataExported(clubId, id, clock.instant(), by, "member-data", "zip", files, "MEMBER_DATA"));
        audit.write(new AuditCommand(AuditAction.MEMBER_DATA_EXPORTED, "ExportJob", id, memberId, null, null, null));
        if (deliver) {
            long days = ExportPolicy.MEMBER_DATA_RETENTION.toDays();
            notifications.notifyDirect("N-50", new com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger(id, "MemberDataReady", clubId, "ExportJob", id,
                    clock.instant(), java.util.Map.of("memberId", memberId, "exportId", id, "link", "/perfil", "expires_days", days), by, null,
                    com.agilityhub.core.shared.domain.DomainEvent.Origin.SYSTEM));
        }
    }
    /**
     * S14 R-14-12 (E11-T10, INC-38): an export changes no field, so its `DATA_EXPORTED` has no `changes` and carries
     * `details {listKey, rows, filters}`, the filters as the list answers them (`appliedFilters`: `{field, op, value}`).
     */
    public record Receipt(String id, String listKey, String format, long rows, java.util.List<java.util.Map<String, Object>> filters) {
        public java.util.Map<String, Object> details() {
            var details = new java.util.LinkedHashMap<String, Object>();
            details.put("listKey", listKey); details.put("rows", rows); details.put("filters", filters);
            return details;
        }
    }
    @Transactional
    @Audited(action = AuditAction.DATA_EXPORTED, entityType = "'ExportJob'", details = "#result.details()")
    public Receipt completed(String id, String key, String format, long rows, java.util.List<com.agilityhub.core.shared.application.contract.ApiContracts.Filter> filters) {
        events.publish(new DataExported(TenantContext.require(), id, clock.instant(), ListAccess.account(), key, format, rows));
        return new Receipt(id, key, format, rows, (filters == null ? java.util.List.<com.agilityhub.core.shared.application.contract.ApiContracts.Filter>of() : filters).stream().map(filter -> {
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("field", filter.field()); row.put("op", filter.op().name()); row.put("value", filter.value());
            return (java.util.Map<String, Object>) row;
        }).toList());
    }
}

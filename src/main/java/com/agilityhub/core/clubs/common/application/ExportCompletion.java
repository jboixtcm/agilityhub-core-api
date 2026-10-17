package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.domain.DataExported;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.application.lists.ListAccess;
import com.agilityhub.core.shared.domain.audit.AuditField;
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
    public record Receipt(String id, @AuditField String listKey, @AuditField String format, @AuditField long rows) { }
    @Transactional
    @Audited(action = AuditAction.DATA_EXPORTED, entityType = "'ExportJob'")
    public Receipt completed(String id, String key, String format, long rows) {
        events.publish(new DataExported(TenantContext.require(), id, clock.instant(), ListAccess.account(), key, format, rows));
        return new Receipt(id, key, format, rows);
    }
}

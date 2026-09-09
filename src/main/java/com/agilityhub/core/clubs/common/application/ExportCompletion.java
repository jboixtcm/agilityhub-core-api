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
    public ExportCompletion(EventPublisher events, Clock clock) { this.events = events; this.clock = clock; }
    public record Receipt(String id, @AuditField String listKey, @AuditField String format, @AuditField long rows) { }
    @Transactional
    @Audited(action = AuditAction.DATA_EXPORTED, entityType = "'ExportJob'")
    public Receipt completed(String id, String key, String format, long rows) {
        events.publish(new DataExported(TenantContext.require(), id, clock.instant(), ListAccess.account(), key, format, rows));
        return new Receipt(id, key, format, rows);
    }
}

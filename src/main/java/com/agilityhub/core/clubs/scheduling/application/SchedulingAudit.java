package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.scheduling.persistence.*;
import com.agilityhub.core.platform.application.audit.*;
import org.springframework.stereotype.Service;

/** Called inside the owning Mongo transaction; the proxy records only the applicable action. */
@Service
public class SchedulingAudit {
    @Audited(action = AuditAction.WEEK_VALIDATED, entityType = "'Week'", before = "#before")
    public Week validated(Week before, Week after) { return after; }
    @Audited(action = AuditAction.CLASS_CANCELLED, entityType = "'ClassSession'", before = "#before")
    public ClassSession cancelled(ClassSession before, ClassSession after) { return after; }
    @Audited(action = AuditAction.CLASS_UPDATED_WITH_BOOKINGS, entityType = "'ClassSession'", before = "#before")
    public ClassSession updated(ClassSession before, ClassSession after) { return after; }
    @Audited(action = AuditAction.CLASS_RISK_EXEMPTION_CHANGED, entityType = "'ClassSession'", before = "#before")
    public ClassSession exempted(ClassSession before, ClassSession after) { return after; }
    @Audited(action = AuditAction.RING_BLOCK_CREATED, entityType = "'RingBlock'")
    public RingBlock created(RingBlock after) { return after; }
    @Audited(action = AuditAction.RING_BLOCK_CANCELLED, entityType = "'RingBlock'", before = "#before")
    public RingBlock cancelledBlock(RingBlock before, RingBlock after) { return after; }
}

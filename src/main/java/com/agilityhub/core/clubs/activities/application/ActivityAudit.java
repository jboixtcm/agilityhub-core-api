package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.platform.application.audit.*;
import org.springframework.stereotype.Service;

@Service
public class ActivityAudit {
    @Audited(action=AuditAction.ACTIVITY_PUBLISHED,entityType="'Activity'",before="#before")
    public Activity published(Activity before,Activity after) { return after; }
    @Audited(action=AuditAction.ACTIVITY_CANCELLED,entityType="'Activity'",before="#before")
    public Activity cancelled(Activity before,Activity after) { return after; }
    @Audited(action=AuditAction.ACTIVITY_UPDATED,entityType="'Activity'",before="#before")
    public Activity updated(Activity before,Activity after) { return after; }
    @Audited(action=AuditAction.ACTIVITY_REGISTERED_BY_CLUB,entityType="'ActivityRegistration'")
    public ActivityRegistration registered(ActivityRegistration after) { return after; }
    @Audited(action=AuditAction.ACTIVITY_REGISTRATION_CANCELLED_BY_CLUB,entityType="'ActivityRegistration'",before="#before",reason="#reason")
    public ActivityRegistration registrationCancelled(ActivityRegistration before,ActivityRegistration after,String reason) { return after; }
}

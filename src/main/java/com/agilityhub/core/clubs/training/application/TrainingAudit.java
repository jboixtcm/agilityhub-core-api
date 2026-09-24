package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.training.persistence.TrainingBooking;
import com.agilityhub.core.platform.application.audit.*;
import org.springframework.stereotype.Service;

/**
 * S14 R-14-09 / S09 R-09-16 training actions, written inside the booking transaction. Impersonated (BACKOFFICE)
 * creation and cancellation carry the admin as actor and the member as impersonated; `reason` is the override
 * reason of a booking over the weekly limit, or the mandatory reason of a late cancellation (ADMIN_LATE).
 */
@Service
public class TrainingAudit {
    @Audited(action = AuditAction.TRAINING_BOOKED_BY_CLUB, entityType = "'TrainingBooking'", member = "#after.memberId", reason = "#reason")
    public TrainingBooking bookedByClub(TrainingBooking after, String reason) { return after; }
    @Audited(action = AuditAction.TRAINING_CANCELLED_BY_CLUB, entityType = "'TrainingBooking'", before = "#before", member = "#after.memberId", reason = "#reason")
    public TrainingBooking cancelledByClub(TrainingBooking before, TrainingBooking after, String reason) { return after; }
}

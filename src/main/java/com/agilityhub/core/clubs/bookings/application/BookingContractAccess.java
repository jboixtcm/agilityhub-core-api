package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.scheduling.application.SchedulingContractAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.ModuleGuard;
import com.agilityhub.core.shared.application.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Read-only tenant, class and module guards of the S08 operations; no side effects. Since E5-T02 the booking reads
 * and the `.ics` token are checked by {@link BookingQueryService}, since E5-T03 the waiting-list entries by
 * {@link WaitlistService#visible}.
 */
@Service
public class BookingContractAccess {
    private final SchedulingContractAccess scheduling;
    private final ModuleGuard modules;
    public BookingContractAccess(SchedulingContractAccess scheduling, ModuleGuard modules) {
        this.scheduling = scheduling; this.modules = modules;
    }
    public void tenant() { TenantContext.require(); }
    public void classSession(String id) { scheduling.classSession(id); }
    /** `waitlistEntryId` on a seat hold needs WAITLIST (S08 §9): 404 MODULE_DISABLED otherwise. */
    public void waitlistModule(boolean needed) { if (needed) { modules.require(TenantContext.require(), Module.WAITLIST); } }
}

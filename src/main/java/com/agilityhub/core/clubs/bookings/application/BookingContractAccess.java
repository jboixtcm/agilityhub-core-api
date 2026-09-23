package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.scheduling.application.SchedulingContractAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.ModuleGuard;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * Read-only tenant, ownership and module guards of the S08 operations still reserved (E5-T01); no side effects.
 * Since E5-T02 the booking reads and the `.ics` token are checked by {@link BookingQueryService}.
 */
@Service
public class BookingContractAccess {
    private final WaitlistEntryRepository waitlist;
    private final SchedulingContractAccess scheduling;
    private final ModuleGuard modules;
    public BookingContractAccess(WaitlistEntryRepository waitlist, SchedulingContractAccess scheduling, ModuleGuard modules) {
        this.waitlist = waitlist; this.scheduling = scheduling; this.modules = modules;
    }
    public void tenant() { TenantContext.require(); }
    public void waitlistEntry(String id, String memberId, boolean staff) {
        var entry = waitlist.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!staff && !entry.memberId().equals(memberId)) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    public void classSession(String id) { scheduling.classSession(id); }
    /** `waitlistEntryId` on a seat hold needs WAITLIST (S08 §9): 404 MODULE_DISABLED otherwise. */
    public void waitlistModule(boolean needed) { if (needed) { modules.require(TenantContext.require(), Module.WAITLIST); } }
}

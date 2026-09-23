package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.scheduling.application.SchedulingContractAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.ModuleGuard;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Read-only tenant, ownership and module guards of the reserved S08 operations (E5-T01); no side effects. */
@Service
public class BookingContractAccess {
    private final BookingRepository bookings;
    private final WaitlistEntryRepository waitlist;
    private final SchedulingContractAccess scheduling;
    private final ModuleGuard modules;
    public BookingContractAccess(BookingRepository bookings, WaitlistEntryRepository waitlist, SchedulingContractAccess scheduling, ModuleGuard modules) {
        this.bookings = bookings; this.waitlist = waitlist; this.scheduling = scheduling; this.modules = modules;
    }
    public void tenant() { TenantContext.require(); }
    /** A member reads only the bookings of their dogs; another member's booking is 404, never 403 (no existence leak). */
    public void booking(String id, String memberId, boolean staff) {
        var booking = bookings.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!staff && !booking.memberId().equals(memberId)) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    /** The `.ics` link carries a signed token instead of a JWT (R-08-08); verification lands with E5-T02. */
    public void calendar(String id, String token) {
        if (token == null || token.isBlank()) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "token")); }
        bookings.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
    public void waitlistEntry(String id, String memberId, boolean staff) {
        var entry = waitlist.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!staff && !entry.memberId().equals(memberId)) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    public void classSession(String id) { scheduling.classSession(id); }
    /** `waitlistEntryId` on a seat hold needs WAITLIST (S08 §9): 404 MODULE_DISABLED otherwise. */
    public void waitlistModule(boolean needed) { if (needed) { modules.require(TenantContext.require(), Module.WAITLIST); } }
}

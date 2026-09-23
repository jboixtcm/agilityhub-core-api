package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.training.persistence.TrainingBookingRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import org.springframework.stereotype.Service;

/** Read-only tenant and ownership guards of the reserved S09 operations (E5-T01). */
@Service
public class TrainingContractAccess {
    private final TrainingBookingRepository bookings;
    public TrainingContractAccess(TrainingBookingRepository bookings) { this.bookings = bookings; }
    public void tenant() { TenantContext.require(); }
    /** Another member's training booking is 404, never 403. */
    public void booking(String id, String memberId, boolean staff) {
        var booking = bookings.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!staff && !booking.memberId().equals(memberId)) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
}

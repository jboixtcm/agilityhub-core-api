package com.agilityhub.core.clubs.bookings.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/** S08 §6 request bodies. */
public final class BookingRequests {
    private BookingRequests() { }
    public record SeatHoldRequest(@NotBlank String classSessionId, @NotBlank String dogId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "[AGAFA LA PLAÇA] of N-15; requires WAITLIST") String waitlistEntryId) { }
    public record BookingRequest(@NotBlank String seatHoldId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Booking to cancel in the same transaction (R-08-09)") String swapBookingId) { }
    public record BookingCancellationRequest(@Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Text of the club (25)") @Size(max = 500) String message) { }
    public record WaitlistEntryRequest(@NotBlank String classSessionId, @NotBlank String dogId) { }
    public record ClaimRequest(@NotBlank String seatHoldId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String swapBookingId) { }
}

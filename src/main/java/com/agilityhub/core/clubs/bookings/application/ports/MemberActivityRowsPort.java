package com.agilityhub.core.clubs.bookings.application.ports;

import java.time.Instant;
import java.util.List;

/**
 * S08 R-08-22 over S07's `ActivityQueryService`: the `ACTIVITY` rows of screen 03 (live registrations, «inscrita») and
 * the «Activitats» block of screen 04 (published, open, not yet registered, admitted for the selected dog's level).
 * Declared here so `clubs.bookings` never imports `clubs.activities`; the adapter lives in `clubs.activities.application`.
 * Both return nothing while ACTIVITIES is off.
 */
public interface MemberActivityRowsPort {
    /** @param state REGISTERED or WAITLISTED; @param endsAtLocal null for an activity without an end time (S07 «Canvis» 24-09) */
    record Row(String id, String state, String title, Instant startsAt, String startsAtLocal, String endsAtLocal, String placeLabel) { }
    record Bookable(String id, String title, String startsAtLocal, Integer freeSeats) { }
    /** Live registrations of the member whose activity has not ended yet. */
    List<Row> live(String memberId);
    List<Bookable> bookable(String memberId, String dogId);
}

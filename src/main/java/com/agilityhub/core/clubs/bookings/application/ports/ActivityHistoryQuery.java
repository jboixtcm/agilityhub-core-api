package com.agilityhub.core.clubs.bookings.application.ports;

import java.time.Instant;
import java.util.List;

/**
 * S10 §7 contract with S07 for the member history (25, R-10-14): the member's activity registrations whose activity
 * starts at or after {@code from} and is done or cancelled. Adapter in `clubs.activities.application`; nothing while
 * ACTIVITIES is off. The live ones (03) are {@link MemberActivityRowsPort}.
 */
public interface ActivityHistoryQuery {
    /**
     * @param id the registration's id; @param activityId its activity's while the member's page of the activity answers
     * (PUBLISHED or FINISHED, S07 §6), else null: 25 links the row to that page (rulings E74, E75)
     * @param state DONE · CANCELLED · CANCELLED_BY_CLUB (S07); @param adminText the club's text of a cancelled activity
     */
    record Item(String id, String activityId, String title, Instant startsAt, String startsAtLocal, String state, String adminText) { }
    List<Item> itemsFor(String memberId, Instant from);
}

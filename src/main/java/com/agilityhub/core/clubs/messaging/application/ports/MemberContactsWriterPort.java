package com.agilityhub.core.clubs.messaging.application.ports;

import java.time.Instant;

/**
 * The two census writes S11 owns (§3: «Escriu també `Member.contactEmails[].bounced`» and the unsubscribe link of
 * R-11-08), inside the caller's transaction. Implemented by the census; the null object writes nothing.
 */
public interface MemberContactsWriterPort {
    /** R-11-08: `contactEmails[address].bounced = true`; `false` when the member has no such address (or it was already marked). */
    boolean markEmailBounced(String memberId, String address);
    /** R-11-08: `notificationPreferences.emailByCategory.CLUB_NEWS = false`; `false` when nothing changed (no member, or already off). */
    boolean unsubscribeClubNews(String memberId, Instant at);
    /**
     * R-11-04 (12, D10): `Member.notificationPreferences` becomes `block` (E7-T03); `false` when the member does not exist or
     * is erased. The null object writes nothing.
     */
    default boolean savePreferences(String memberId, java.util.Map<String, Object> block) { return false; }
    /**
     * S04 R-04-06 (INC-39, E11-T10): D10's write of the member's block (`PUT /members/{id}/notification-preferences`) refuses a
     * `LEFT` record with a pending readmission, 409 INVALID_STATE (READMISSION_PENDING); the member's own route (12) is not frozen.
     * The null object refuses nothing.
     */
    default void requireNotFrozen(String memberId) { }
}

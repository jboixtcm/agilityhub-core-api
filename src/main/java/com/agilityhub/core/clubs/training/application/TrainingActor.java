package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.training.domain.TrainingCancelledBy;
import com.agilityhub.core.clubs.training.domain.TrainingOrigin;
import com.agilityhub.core.shared.application.CurrentUser;

/**
 * S09 R-09-16 who acts. `accountId` is the acting account (the admin's when impersonating), `memberId` the member on
 * whose behalf the booking runs (the booker), `impersonatedMemberId` is set only for BACKOFFICE. `by` is the
 * `cancelledBy` value of a cancellation: MEMBER for the member route (also impersonated), ADMIN for the club
 * (`cancelBookings: true`, or the impersonating admin's late cancellation), SYSTEM for S13/S15/S03 paths.
 */
public record TrainingActor(String accountId, String memberId, String displayName, String impersonatedMemberId, TrainingOrigin origin, TrainingCancelledBy by,
        boolean notifies) {
    /** An actor whose training actions notify the member as the catalogue says (COMM-03: only {@link #notifying} turns it off). */
    public TrainingActor(String accountId, String memberId, String displayName, String impersonatedMemberId, TrainingOrigin origin, TrainingCancelledBy by) {
        this(accountId, memberId, displayName, impersonatedMemberId, origin, by, true);
    }
    /**
     * COMM-03 (owner's decision of 13-11): an ADMIN booking or cancelling a training slot for a member (impersonation, BACKOFFICE)
     * may ask not to notify the member (`notifyMember: false`: no N-47; the events carry `notifyMember: false` and the audit entry records it).
     * Anyone else asking it (the member, an instructor) → 403 FORBIDDEN; null or true changes nothing.
     */
    public TrainingActor notifying(Boolean notify) {
        if (notify == null || notify) { return this; }
        if (!adminDelegation()) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.FORBIDDEN); }
        return new TrainingActor(accountId, memberId, displayName, impersonatedMemberId, origin, by, false);
    }
    public boolean adminDelegation() { return impersonated() && origin == TrainingOrigin.BACKOFFICE; }
    public boolean impersonated() { return impersonatedMemberId != null; }
    public static TrainingActor system() { return new TrainingActor(null, null, null, null, TrainingOrigin.BACKOFFICE, TrainingCancelledBy.SYSTEM); }
    /** The member route caller: the JWT's member, or the impersonated one with the admin as actor. */
    public static TrainingActor member(String jwtMemberId) {
        var user = CurrentUser.current();
        if (user != null && user.impersonation() != null) {
            var imp = user.impersonation();
            return new TrainingActor(imp.actorAccountId(), imp.memberId(), imp.actorName(), imp.memberId(), imp.bookingsOnly() ? TrainingOrigin.INSTRUCTOR : TrainingOrigin.BACKOFFICE, TrainingCancelledBy.MEMBER);
        }
        return new TrainingActor(user == null ? null : user.accountId(), jwtMemberId, user == null ? null : user.name(), null, TrainingOrigin.APP, TrainingCancelledBy.MEMBER);
    }
    /** An administrator acting from the back office through S05/S06/S07 (`cancelBookings: true`). */
    public static TrainingActor club() {
        var user = CurrentUser.current();
        return new TrainingActor(user == null ? null : user.accountId(), null, user == null ? null : user.name(), null, TrainingOrigin.BACKOFFICE, TrainingCancelledBy.ADMIN);
    }
    public TrainingActor as(TrainingCancelledBy other) { return new TrainingActor(accountId, memberId, displayName, impersonatedMemberId, origin, other, notifies); }
}

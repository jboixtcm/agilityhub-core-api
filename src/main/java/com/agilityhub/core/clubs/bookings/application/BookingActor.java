package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.shared.application.CurrentUser;

/**
 * S08 R-08-19 who acts. `accountId` is the acting account (the admin's when impersonating; it also owns the seat
 * hold), `memberId` the member on whose behalf the action runs (the booker), `impersonatedMemberId` is set only
 * for BACKOFFICE. SYSTEM has neither account nor member (S13/S15 cancellations, payment consumers).
 */
public record BookingActor(String accountId, String memberId, String displayName, String impersonatedMemberId, BookingOrigin origin, ActorRole role) {
    public boolean impersonated() { return impersonatedMemberId != null; }
    public boolean isSystem() { return role == ActorRole.SYSTEM; }
    public static BookingActor system() { return new BookingActor(null, null, null, null, BookingOrigin.SYSTEM, ActorRole.SYSTEM); }
    /** The member route caller: the JWT's member, or the impersonated one with the admin as actor. */
    public static BookingActor member(String accountId, String memberId, String displayName) {
        var user = CurrentUser.current();
        if (user != null && user.impersonation() != null) {
            var imp = user.impersonation();
            return new BookingActor(imp.actorAccountId(), imp.memberId(), imp.actorName(), imp.memberId(), BookingOrigin.BACKOFFICE, ActorRole.ADMIN);
        }
        return new BookingActor(accountId, memberId, displayName, null, BookingOrigin.APP, ActorRole.MEMBER);
    }
    /** The «ha avisat» cancellation from screen 21 (S10 calls the service with this actor). */
    public static BookingActor instructor(String accountId, String displayName) {
        return new BookingActor(accountId, null, displayName, null, BookingOrigin.INSTRUCTOR, ActorRole.INSTRUCTOR);
    }
}

package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.shared.application.CurrentUser;
import org.springframework.stereotype.Service;

/** Builds the {@link BookingActor} of the current request (R-08-19): member, impersonating admin or instructor. */
@Service
public class BookingActors {
    private final BookingMemberAccess census;
    public BookingActors(BookingMemberAccess census) { this.census = census; }
    /**
     * Only called from authenticated club routes, where `CurrentUserFilter` always opened the current user.
     * @param jwtMemberId the club token's `memberId` claim (ignored while impersonating: the impersonated member acts)
     */
    public BookingActor member(String jwtMemberId) {
        var user = CurrentUser.current();
        String name = census.member(jwtMemberId).map(BookingMemberAccess.Member::firstName).orElse(user.name());
        return BookingActor.member(user.accountId(), jwtMemberId, name);
    }
    /** An administrator acting from the back office without impersonation (D4/D12: removing a waiting-list entry). */
    public BookingActor admin() {
        var user = CurrentUser.current();
        return new BookingActor(user.accountId(), null, user.name(), null, com.agilityhub.core.clubs.bookings.domain.BookingOrigin.BACKOFFICE,
                com.agilityhub.core.clubs.bookings.domain.ActorRole.ADMIN);
    }
    public BookingActor instructor() { var user = CurrentUser.current(); return BookingActor.instructor(user.accountId(), user.name()); }
}

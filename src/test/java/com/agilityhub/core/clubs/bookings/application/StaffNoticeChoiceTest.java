package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * COMM-03 (owner's decision of 13-11): a booking or cancellation an ADMIN makes for a member may skip the member's notice (N-36).
 * Only an ADMIN acting for the member may ask it; the booking events then carry `notifyMember: false` and N-36 does not apply.
 */
class StaffNoticeChoiceTest {
    static final BookingActor ADMIN = new BookingActor("admin-account", "member-a", "Fictional Admin", "member-a", BookingOrigin.BACKOFFICE, ActorRole.ADMIN);
    static final BookingActor DELEGATED_INSTRUCTOR = new BookingActor("instructor-account", "member-a", "Fictional Instructor", "member-a", BookingOrigin.INSTRUCTOR,
            ActorRole.INSTRUCTOR);
    static final BookingActor MEMBER = new BookingActor("member-account", "member-a", "Fictional Member", null, BookingOrigin.APP, ActorRole.MEMBER);
    static final BookingActor INSTRUCTOR = BookingActor.instructor("instructor-account", "Fictional Instructor");

    @Test void COMM_03_onlyAnAdminActingForTheMemberMaySkipTheNotice() {
        assertThat(ADMIN.notifies()).isTrue();
        var silent = ADMIN.notifying(false);
        assertThat(silent.notifies()).isFalse();
        assertThat(silent).isEqualTo(new BookingActor("admin-account", "member-a", "Fictional Admin", "member-a", BookingOrigin.BACKOFFICE, ActorRole.ADMIN, false));
        assertThat(ADMIN.notifying(null)).isSameAs(ADMIN);
        assertThat(ADMIN.notifying(true)).isSameAs(ADMIN);
        for (var actor : new BookingActor[] {MEMBER, INSTRUCTOR, DELEGATED_INSTRUCTOR, BookingActor.system()}) {
            assertThat(actor.notifying(null)).isSameAs(actor);
            assertThat(actor.notifying(true)).isSameAs(actor);
            assertThatThrownBy(() -> actor.notifying(false)).as(actor.role().name())
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code()).isEqualTo(ErrorCode.FORBIDDEN));
        }
    }

    @Test void COMM_03_onlyTheBookingEventsOfASilencedActorCarryNotifyFalse() {
        Map<String, Object> payload = Map.of("bookingId", "booking-a", "origin", "BACKOFFICE");
        var silent = ADMIN.notifying(false);
        for (var kind : new BookingEvent.Kind[] {BookingEvent.Kind.BookingCreated, BookingEvent.Kind.BookingCancelled}) {
            assertThat(BookingEvents.silenced(kind, payload, silent)).containsEntry("notifyMember", false).containsEntry("bookingId", "booking-a");
            assertThat(BookingEvents.silenced(kind, payload, ADMIN)).isSameAs(payload);
        }
        assertThat(BookingEvents.silenced(BookingEvent.Kind.SeatReleased, payload, silent)).isSameAs(payload);
        assertThat(payload).doesNotContainKey("notifyMember");
    }

    @Test void COMM_03_n36DoesNotApplyToABookingEventWithNotifyFalse() {
        for (String type : new String[] {"BookingCreated", "BookingCancelled"}) {
            var club = new LinkedHashMap<String, Object>(Map.of("bookingId", "booking-a", "origin", "BACKOFFICE", "by", "ADMIN"));
            assertThat(BookingNotificationFacts.applies("N-36", trigger(type, club))).as(type).isTrue();
            club.put("notifyMember", true);
            assertThat(BookingNotificationFacts.applies("N-36", trigger(type, club))).as(type).isTrue();
            club.put("notifyMember", false);
            assertThat(BookingNotificationFacts.applies("N-36", trigger(type, club))).as(type).isFalse();
            // The silenced club action never falls back on the member's own notices.
            assertThat(BookingNotificationFacts.applies("N-04", trigger(type, club))).isFalse();
            assertThat(BookingNotificationFacts.applies("N-05", trigger(type, club))).isFalse();
        }
    }

    static NotificationTrigger trigger(String type, Map<String, Object> payload) {
        return new NotificationTrigger("event-1", type, "club-a", "Booking", "booking-a", Instant.parse("2026-10-05T08:00:00Z"), payload, "admin-account",
                "member-a", DomainEvent.Origin.BACKOFFICE);
    }
}

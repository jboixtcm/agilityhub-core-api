package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.platform.application.privacy.ErasureBlockReason;
import com.agilityhub.core.platform.application.privacy.ErasureCheck;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.platform.application.privacy.ErasureMember;
import com.agilityhub.core.platform.application.privacy.MemberDataPackage;
import com.agilityhub.core.platform.application.privacy.MemberDataSource;
import com.agilityhub.core.clubs.bookings.persistence.BookingErasureRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * S14 WP-14-D, S08/S10: R-14-14's `FUTURE_BOOKINGS` (an `ACTIVE` or `PAYMENT_PENDING` booking of a class still to start) and
 * R-14-15's bookings (ids, states, dates kept; the club's free text goes, and the member's name as booker or canceller goes from
 * every booking of the club, another member's too: E11-T16), seat holds deleted. Waiting-list entries and attendances hold no
 * personal text of the member and are kept; an attendance the erased account marked loses the marker's name.
 */
@Component
public class BookingsErasure implements ErasureCheck, ErasureStep, MemberDataSource {
    private final BookingErasureRepository erasure;
    public BookingsErasure(BookingErasureRepository erasure) { this.erasure = erasure; }

    @Override public Set<ErasureBlockReason> blockReasons(Collection<String> memberIds, Instant now) {
        return erasure.futureBookings(memberIds, now) ? EnumSet.of(ErasureBlockReason.FUTURE_BOOKINGS) : EnumSet.noneOf(ErasureBlockReason.class);
    }

    @Override public ErasureEffect erase(ErasureSubject subject) {
        var effect = ErasureEffect.builder();
        erasure.erase(subject.memberIds(), subject.accountIds()).forEach(effect::count);
        return effect.build();
    }

    /** S14 R-14-13: `bookings[]`, `waitlistEntries[]` and `attendances[]`. */
    @Override public void contribute(ErasureMember member, MemberDataPackage data) {
        data.section("bookings", erasure.rows("bookings", member.id()).stream().map(MemberDataPackage::row).toList());
        data.section("waitlistEntries", erasure.rows("waitlist_entries", member.id()).stream().map(MemberDataPackage::row).toList());
        data.section("attendances", erasure.rows("attendances", member.id()).stream().map(MemberDataPackage::row).toList());
    }
}

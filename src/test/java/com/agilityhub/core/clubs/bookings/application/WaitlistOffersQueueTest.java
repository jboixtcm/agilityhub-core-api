package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.WaitlistConsolidationPort.Offer;
import com.agilityhub.core.clubs.bookings.domain.WaitlistMode;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.SeatHold;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.platform.application.Module;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S08 R-08-07, R-08-14, R-08-15 — INC-40 (E11-T10 review): what other dogs take against a hold or a confirmation. The claim of
 * the dog's own open FIFO offer is ahead of the holds taken up to its `notifiedAt` and of the offers behind it in the queue.
 */
class WaitlistOffersQueueTest {
    static final Instant OFFERED = Instant.parse("2026-10-06T08:00:00Z");

    static SeatHold hold(String dogId, Instant createdAt) {
        return new SeatHold("hold-" + dogId, "club-a", "class-a", dogId, "member-" + dogId, "account-" + dogId, null, createdAt, createdAt.plusSeconds(30));
    }

    @Test void T_08_21_INC_40_aPlainHoldOrConfirmationCountsEveryOtherHoldAndOffer() {
        var offers = List.of(new Offer("entry-duna", "dog-duna", OFFERED, 1));
        var taken = WaitlistTransitions.taken(offers, List.of(hold("dog-c1", OFFERED.minusSeconds(5)), hold("dog-own", OFFERED)), "dog-own", null);
        assertThat(taken.offered()).containsExactly("dog-duna");
        assertThat(taken.dogs()).containsExactlyInAnyOrder("dog-duna", "dog-c1");
    }

    @Test void T_08_21_INC_40_theClaimOfTheOwnOfferSkipsTheHoldsTakenUpToItsNotification() {
        var offers = List.of(new Offer("entry-duna", "dog-duna", OFFERED, 1));
        var live = List.of(hold("dog-before", OFFERED.minusSeconds(5)), hold("dog-same", OFFERED), hold("dog-after", OFFERED.plusSeconds(1)));
        var taken = WaitlistTransitions.taken(offers, live, "dog-duna", "entry-duna");
        assertThat(taken.offered()).isEmpty();
        assertThat(taken.dogs()).containsExactly("dog-after");
    }

    @Test void T_08_45_INC_40_theClaimCountsOnlyTheOffersAheadAndTheirHolds() {
        var duna = new Offer("entry-duna", "dog-duna", OFFERED, 1); var c0 = new Offer("entry-c0", "dog-c0", OFFERED, 2);
        var c1 = new Offer("entry-c1", "dog-c1", OFFERED.plusSeconds(60), 3);
        var live = List.of(hold("dog-duna", OFFERED.plusSeconds(10)), hold("dog-c1", OFFERED.plusSeconds(70)));
        var ahead = WaitlistTransitions.taken(List.of(duna, c0, c1), live, "dog-c0", "entry-c0");
        assertThat(ahead.offered()).containsExactly("dog-duna"); assertThat(ahead.dogs()).containsExactly("dog-duna");
        var first = WaitlistTransitions.taken(List.of(duna, c0, c1), live, "dog-duna", "entry-duna");
        assertThat(first.offered()).isEmpty(); assertThat(first.dogs()).isEmpty();
    }

    @Test void T_08_22_INC_40_aClaimWhoseEntryIsNoOpenOfferOfTheDogCountsEverything() {
        var offers = List.of(new Offer("entry-duna", "dog-duna", OFFERED, 1));
        var live = List.of(hold("dog-before", OFFERED.minusSeconds(5)));
        assertThat(WaitlistTransitions.taken(offers, live, "dog-toby", "entry-duna").dogs()).containsExactlyInAnyOrder("dog-duna", "dog-before");
        assertThat(WaitlistTransitions.taken(offers, live, "dog-toby", "entry-gone").dogs()).containsExactlyInAnyOrder("dog-duna", "dog-before");
        var unstamped = List.of(new Offer("entry-toby", "dog-toby", null, 1));
        assertThat(WaitlistTransitions.taken(unstamped, live, "dog-toby", "entry-toby").dogs()).containsExactly("dog-before");
    }

    @Test void T_08_45_INC_40_theOpenOffersComeInQueueOrderAndOnlyInFifo() {
        var context = mock(BookingContext.class); var waitlist = mock(WaitlistEntryRepository.class);
        when(context.enabled(Module.WAITLIST)).thenReturn(true); when(context.waitlistMode()).thenReturn(WaitlistMode.FIFO);
        when(waitlist.openOffers(any(), any())).thenReturn(List.of(entry("entry-late", "dog-late", OFFERED.plusSeconds(60), 1),
                entry("entry-b", "dog-b", OFFERED, 3), entry("entry-a", "dog-a", OFFERED, 2)));
        assertThat(WaitlistTransitions.openOffers(context, waitlist, "class-a", OFFERED).stream().map(Offer::entryId))
                .containsExactly("entry-a", "entry-b", "entry-late");
        when(context.waitlistMode()).thenReturn(WaitlistMode.ALL_AT_ONCE);
        assertThat(WaitlistTransitions.openOffers(context, waitlist, "class-a", OFFERED)).isEmpty();
        when(context.waitlistMode()).thenReturn(WaitlistMode.FIFO); when(context.enabled(Module.WAITLIST)).thenReturn(false);
        assertThat(WaitlistTransitions.openOffers(context, waitlist, "class-a", OFFERED)).isEmpty();
    }

    static WaitlistEntry entry(String id, String dogId, Instant notifiedAt, int position) {
        return new WaitlistEntry(id, "club-a", "class-a", dogId, "member-" + dogId, "account-" + dogId, OFFERED.minusSeconds(3600), WaitlistState.NOTIFIED,
                position, notifiedAt, notifiedAt.plusSeconds(1800), null, null, null, null, OFFERED.plusSeconds(86400), "2026-W41", 1L,
                OFFERED, null, OFFERED, null);
    }
}

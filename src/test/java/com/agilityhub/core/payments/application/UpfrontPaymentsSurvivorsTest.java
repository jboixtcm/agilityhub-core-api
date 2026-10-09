package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.application.UpfrontPayments.Charge;
import com.agilityhub.core.payments.application.UpfrontPayments.Submission;
import com.agilityhub.core.payments.application.ports.PackBalanceOpeningPort;
import com.agilityhub.core.payments.domain.SignupPaymentEvent;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.payments.persistence.UpfrontPayment;
import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.shared.application.ClubClock;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link UpfrontPayments} (S12 R-12-23, T-12-16; S04 R-04-16, T-04-07; S04 §5 rulings E39/E39b): a
 * `PACK` row opens its pack on the club-local day it becomes fully `PAID` (by hand or by Checkout), and the Checkout event
 * names no pack balance its row does not have; the entry fee comes first among rows of one instant; a PAY_TO_BOOK line
 * is no signup submission owed; a plan change deducts what a `PAID` row received and writes each new row under its own dog's
 * submission.
 */
class UpfrontPaymentsSurvivorsTest {
    static final String CLUB = "club-a", MEMBER = "member-1";
    /** 23:30 UTC is already the next day in the club's zone. */
    static final Instant NOW = Instant.parse("2026-09-14T23:30:00Z");
    static final ZoneId ZONE = ZoneId.of("Europe/Madrid");

    final UpfrontPaymentRepository repository = mock(UpfrontPaymentRepository.class);
    final SignupCheckoutRepository sessions = mock(SignupCheckoutRepository.class);
    final EventPublisher events = mock(EventPublisher.class);
    final PackBalanceOpeningPort packs = mock(PackBalanceOpeningPort.class);
    final ClubClock clubClock = mock(ClubClock.class);
    final UpfrontPayments payments = new UpfrontPayments(repository, sessions, events, Clock.fixed(NOW, ZoneOffset.UTC), packs, clubClock);

    @BeforeEach void wire() {
        ReflectionTestUtils.setField(payments, "audit", mock(PaymentAudits.class));
        when(clubClock.now(CLUB)).thenReturn(NOW.atZone(ZONE));
    }

    @Test void T_12_16_aPackPaidInFullByHandOpensItsPackOnTheClubLocalDay() {
        when(repository.member(MEMBER)).thenReturn(List.of(row("payment-pack", "PACK", "dog-1", 13500, 0, "DUE", null, NOW.minusSeconds(3600), null, "sub-1")));

        try (var tenant = TenantContext.open(CLUB)) { payments.allocate(MEMBER, null, eur(13500)); }

        verify(packs).open(MEMBER, "dog-1", "payment-pack", LocalDate.of(2026, 9, 15));
    }

    @Test void T_12_16_aPackPaidInPartDoesNotOpenYet() {
        when(repository.member(MEMBER)).thenReturn(List.of(row("payment-pack", "PACK", "dog-1", 13500, 0, "DUE", null, NOW.minusSeconds(3600), null, "sub-1")));

        try (var tenant = TenantContext.open(CLUB)) { payments.allocate(MEMBER, null, eur(5000)); }

        verify(packs, never()).open(any(), any(), any(), any());
    }

    /**
     * Rows as {@link UpfrontPayments#pending} leaves them (legacy constructor: `provider = STRIPE`, the session, no
     * `packBalanceId`; only a manual `PAID` PACK row gets one, ManualUpfrontPayments:39-41, and such a row never goes
     * `CHECKOUT_PENDING`).
     */
    @Test void T_12_16_aCompletedCheckoutOpensItsPackAndNamesNoPackBalanceItsRowsDoNotHave() {
        Instant paidAt = Instant.parse("2026-09-20T22:15:00Z");
        var pack = new UpfrontPayment("payment-pack", CLUB, MEMBER, "dog-1", "PACK", "PACK", eur(13500), eur(0), "CHECKOUT_PENDING", "STRIPE",
                "cs_fake_1", NOW.minusSeconds(3600), null, null, "sub-1", null);
        var entry = new UpfrontPayment("payment-entry", CLUB, MEMBER, "dog-1", "ENTRY_FEE", "ENTRY_FEE", eur(3000), eur(0), "CHECKOUT_PENDING", "STRIPE",
                "cs_fake_1", NOW.minusSeconds(3600), null, null, "sub-1", null);
        when(repository.member(MEMBER)).thenReturn(List.of(pack, entry));

        payments.checkout(MEMBER, "cs_fake_1", true, paidAt);

        verify(packs).open(MEMBER, "dog-1", "payment-pack", LocalDate.of(2026, 9, 21));
        var published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events, times(2)).publish(published.capture());
        var payloads = published.getAllValues().stream().map(event -> ((SignupPaymentEvent) event).payload()).toList();
        assertThat(payloads.get(0)).containsEntry("paymentId", "payment-pack").doesNotContainKey("packBalanceId");
        assertThat(payloads.get(1)).containsEntry("paymentId", "payment-entry").doesNotContainKey("packBalanceId");
    }

    @Test void T_04_07_theEntryFeeComesFirstAmongRowsOfOneInstant() {
        when(repository.member(MEMBER)).thenReturn(List.of(
                row("payment-a", "FIRST_MONTH", "dog-1", 4500, 0, "DUE", null, NOW, null, "sub-1"),
                row("payment-b", "ENTRY_FEE", "dog-1", 3000, 0, "DUE", null, NOW, null, "sub-1")));

        assertThat(payments.lines(MEMBER, null)).extracting(UpfrontPayments.Line::id).containsExactly("payment-b", "payment-a");
    }

    @Test void E11_T06_aPayToBookLineIsNoSignupSubmissionOwed() {
        when(repository.member(MEMBER)).thenReturn(List.of(
                row("payment-entry", "ENTRY_FEE", "dog-1", 3000, 0, "DUE", null, NOW.minusSeconds(60), null, "sub-1"),
                row("payment-class", "SINGLE_CLASS", "dog-2", 1500, 0, "DUE", null, NOW, "booking-1", null)));

        assertThat(payments.owed(MEMBER)).containsExactly(entry(new Submission("dog-1", "sub-1"), Optional.of(NOW.minusSeconds(60))));
    }

    @Test void E11_T06_aReplacementRowBelongsToTheSubmissionOfItsOwnDog() {
        when(repository.member(MEMBER)).thenReturn(List.of());
        var scope = List.of(new Submission("dog-1", "sub-1"), new Submission("dog-2", "sub-2"));

        try (var tenant = TenantContext.open(CLUB)) { payments.replace(MEMBER, scope, List.of(new Charge("FIRST_MONTH", "dog-2", eur(4500)))); }

        var inserted = ArgumentCaptor.forClass(UpfrontPayment.class);
        verify(repository).insert(inserted.capture());
        assertThat(inserted.getValue().dogId()).isEqualTo("dog-2");
        assertThat(inserted.getValue().submissionId()).isEqualTo("sub-2");
    }

    @Test void E11_T06_whatAPaidRowReceivedIsDeductedFromTheNewQuoteAndWhatExceedsItIsReported() {
        when(repository.member(MEMBER)).thenReturn(List.of(row("payment-paid", "FIRST_MONTH", "dog-1", 3000, 3000, "PAID", null, NOW, null, "sub-1")));
        var scope = List.of(new Submission("dog-1", "sub-1"));

        var larger = payments.replacement(MEMBER, scope, List.of(new Charge("FIRST_MONTH", "dog-1", eur(5000))));
        assertThat(larger.created()).containsExactly(new Charge("FIRST_MONTH", "dog-1", eur(2000)));
        assertThat(larger.paidExceedsQuote()).isNull();

        var smaller = payments.replacement(MEMBER, scope, List.of(new Charge("FIRST_MONTH", "dog-1", eur(1000))));
        assertThat(smaller.created()).isEmpty();
        assertThat(smaller.paidExceedsQuote()).isEqualTo(eur(2000));
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static Money eur(long amount) { return new Money(amount, "EUR"); }

    static UpfrontPayment row(String id, String concept, String dogId, long due, long paid, String status, String session, Instant createdAt,
            String bookingId, String submissionId) {
        return new UpfrontPayment(id, CLUB, MEMBER, dogId, concept, concept, eur(due), eur(paid), status, null, session, createdAt, null, bookingId,
                submissionId, null);
    }
}

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
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    // --- INC-39 (E11-T10): T-04-07 and T-04-22 on the production writers (the domain copies they tested were dead code) ------

    /** T-04-07 (R-04-16), INC-39 (E11-T10): S04's cash cascade on the production allocation, the entry fee before the first month. */
    @ParameterizedTest
    @CsvSource({"13000,PAID,10000,PAID,3000", "10000,PAID,10000,DUE,0", "5000,PARTIAL,5000,DUE,0", "0,DUE,0,DUE,0"})
    void T_04_07_INC_39_theCashCascadeOfS04OnTheProductionAllocation(long amount, String entryStatus, long entryPaid, String monthStatus, long monthPaid) {
        var rows = new HashMap<String, UpfrontPayment>();
        rows.put("payment-month", row("payment-month", "FIRST_MONTH", "dog-1", 3000, 0, "DUE", null, NOW, null, "sub-1"));
        rows.put("payment-entry", row("payment-entry", "ENTRY_FEE", "dog-1", 10000, 0, "DUE", null, NOW, null, "sub-1"));
        when(repository.member(MEMBER)).thenReturn(List.of(rows.get("payment-month"), rows.get("payment-entry")));
        doAnswer(call -> { UpfrontPayment p = call.getArgument(0); rows.put(p.id(), p); return null; }).when(repository).update(any());

        try (var tenant = TenantContext.open(CLUB)) { payments.allocate(MEMBER, null, eur(amount)); }

        assertThat(rows.get("payment-entry")).extracting(UpfrontPayment::status, p -> p.amountPaid().amountMinor()).containsExactly(entryStatus, entryPaid);
        assertThat(rows.get("payment-month")).extracting(UpfrontPayment::status, p -> p.amountPaid().amountMinor()).containsExactly(monthStatus, monthPaid);
    }

    /** T-04-07 (R-04-16), INC-39 (E11-T10): 131 € against 130 € due is UPFRONT_AMOUNT_EXCEEDS_DUE, and nothing is written. */
    @Test void T_04_07_INC_39_anAmountAboveWhatIsDueIsRefusedAndNothingIsWritten() {
        when(repository.member(MEMBER)).thenReturn(List.of(row("payment-entry", "ENTRY_FEE", "dog-1", 10000, 0, "DUE", null, NOW, null, "sub-1"),
                row("payment-month", "FIRST_MONTH", "dog-1", 3000, 0, "DUE", null, NOW, null, "sub-1")));

        try (var tenant = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> payments.allocate(MEMBER, null, eur(13100))).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.code()).isEqualTo(ErrorCode.UPFRONT_AMOUNT_EXCEEDS_DUE));
        }

        verify(repository, never()).update(any());
    }

    /** The edges of S04 §5's `UpfrontPayment.status` diagram; `[*]` is a new row. */
    static final Set<String> S04_EDGES = Set.of("[*] → DUE", "DUE → CHECKOUT_PENDING", "CHECKOUT_PENDING → PAID", "CHECKOUT_PENDING → DUE", "DUE → PAID",
            "DUE → PARTIAL", "PARTIAL → PAID", "DUE → CANCELLED", "PARTIAL → CANCELLED", "CHECKOUT_PENDING → CANCELLED", "PAID → REFUNDED");
    /**
     * What the writers do that the diagram does not draw, with where it was decided. [*] → PAID: the correction row of a closed
     * PARTIAL row, in §5's text (ruling E39b). INC-39.2 (ruling E149, E11-T17): a PARTIAL row no longer enters a checkout, so
     * PARTIAL → CHECKOUT_PENDING and CHECKOUT_PENDING → PARTIAL are no exception any more.
     */
    static final Map<String, String> DOCUMENTED = Map.of(
            "[*] → PAID", "S04 §5 text, ruling E39b: the PAID correction of a closed PARTIAL row");

    /** Runs a writer over {@code rows} and returns each status change it writes: `before → after`, or `[*] → status` for a new row. */
    Set<String> changes(List<UpfrontPayment> rows, Runnable writer) {
        reset(repository);
        var before = new HashMap<String, String>(); rows.forEach(p -> before.put(p.id(), p.status()));
        var changes = new LinkedHashSet<String>();
        when(repository.member(MEMBER)).thenReturn(rows);
        doAnswer(call -> { UpfrontPayment p = call.getArgument(0); if (!p.status().equals(before.get(p.id()))) { changes.add(before.get(p.id()) + " → " + p.status()); } return null; })
                .when(repository).update(any());
        doAnswer(call -> { UpfrontPayment p = call.getArgument(0); changes.add("[*] → " + p.status()); return p; }).when(repository).insert(any());
        try (var tenant = TenantContext.open(CLUB)) { writer.run(); }
        return changes;
    }
    static UpfrontPayment waiting(String id, long due, long paid, String bookingId) {
        return new UpfrontPayment(id, CLUB, MEMBER, "dog-1", "ENTRY_FEE", "ENTRY_FEE", eur(due), eur(paid), "CHECKOUT_PENDING", "STRIPE", "cs_fake_1", NOW, null, bookingId, "sub-1", null);
    }

    /**
     * T-04-22 (R-04-26, S04 §5), INC-39 (E11-T10): every status change the production writers of {@link UpfrontPayments} make is
     * an edge of S04 §5's diagram or one of the documented exceptions, and each of those is really made (so the list cannot
     * rot). The refund (PAID → REFUNDED) is S12's writer. INC-39.2 (ruling E149, E11-T17): only a DUE row waits for a checkout;
     * a PARTIAL row meets it through {@link UpfrontPayments#toCharge}, which closes it (E39b) and charges its rest as a new DUE row.
     */
    @Test void T_04_22_INC_39_everyStatusChangeOfTheWritersIsAnEdgeOfS04sDiagramOrADocumentedException() {
        var scope = List.of(new Submission("dog-1", "sub-1"));
        var produced = new LinkedHashSet<String>();
        produced.addAll(changes(List.of(), () -> payments.create(MEMBER, "sub-1", List.of(new Charge("ENTRY_FEE", "dog-1", eur(10000))))));
        produced.addAll(changes(List.of(), () -> payments.createForBooking(MEMBER, new Charge("SINGLE_CLASS", "dog-1", eur(1500)), "booking-1")));
        produced.addAll(changes(List.of(row("p-due", "ENTRY_FEE", "dog-1", 10000, 0, "DUE", null, NOW, null, "sub-1")), () -> payments.allocate(MEMBER, scope, eur(10000))));
        produced.addAll(changes(List.of(row("p-due", "ENTRY_FEE", "dog-1", 10000, 0, "DUE", null, NOW, null, "sub-1")), () -> payments.allocate(MEMBER, scope, eur(4000))));
        produced.addAll(changes(List.of(row("p-partial", "ENTRY_FEE", "dog-1", 10000, 4000, "PARTIAL", null, NOW, null, "sub-1")), () -> payments.allocate(MEMBER, scope, eur(6000))));
        produced.addAll(changes(List.of(row("p-open", "ENTRY_FEE", "dog-1", 10000, 0, "DUE", null, NOW, null, "sub-1")),
                () -> payments.pending(MEMBER, List.of("p-open"), "cs_fake_1")));
        for (var status : List.of("PARTIAL", "CHECKOUT_PENDING", "PAID", "CANCELLED", "REFUNDED")) {
            var closed = List.of(row("p-closed", "ENTRY_FEE", "dog-1", 10000, status.equals("PARTIAL") ? 4000 : 0, status, null, NOW, null, "sub-1"));
            assertThatThrownBy(() -> changes(closed, () -> payments.pending(MEMBER, List.of("p-closed"), "cs_fake_2"))).as(status)
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code()).isEqualTo(ErrorCode.INVALID_STATE));
            if (!status.equals("PARTIAL")) {
                assertThatThrownBy(() -> changes(closed, () -> payments.toCharge(MEMBER, List.of("p-closed")))).as("toCharge %s", status)
                        .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code()).isEqualTo(ErrorCode.INVALID_STATE));
            }
        }
        produced.addAll(changes(List.of(row("t-due", "ENTRY_FEE", "dog-1", 10000, 0, "DUE", null, NOW, null, "sub-1"),
                row("t-partial", "FIRST_MONTH", "dog-1", 6000, 4000, "PARTIAL", null, NOW, null, "sub-1")), () -> payments.toCharge(MEMBER, List.of("t-due", "t-partial"))));
        produced.addAll(changes(List.of(waiting("p-paying", 10000, 0, null)), () -> payments.checkout(MEMBER, "cs_fake_1", true)));
        produced.addAll(changes(List.of(waiting("p-expiring", 10000, 0, null), waiting("p-booking", 1500, 0, "booking-1")),
                () -> payments.checkout(MEMBER, "cs_fake_1", false)));
        var open = List.of(row("r-due", "ENTRY_FEE", "dog-1", 10000, 0, "DUE", null, NOW, null, "sub-1"), row("r-partial", "FIRST_MONTH", "dog-1", 6000, 1000, "PARTIAL", null, NOW, null, "sub-1"),
                waiting("r-waiting", 3000, 0, null), row("r-paid", "PACK", "dog-1", 2000, 2000, "PAID", null, NOW, null, "sub-1"));
        produced.addAll(changes(open, () -> payments.reject(MEMBER, scope)));
        var replaced = List.of(row("c-due", "ENTRY_FEE", "dog-1", 10000, 0, "DUE", null, NOW, null, "sub-1"), row("c-partial", "FIRST_MONTH", "dog-1", 6000, 1000, "PARTIAL", null, NOW, null, "sub-1"),
                row("c-paid", "PACK", "dog-1", 2000, 2000, "PAID", null, NOW, null, "sub-1"));
        produced.addAll(changes(replaced, () -> payments.replace(MEMBER, scope, List.of(new Charge("PACK", "dog-1", eur(13500))))));

        var allowed = new HashSet<>(S04_EDGES); allowed.addAll(DOCUMENTED.keySet());
        assertThat(produced).as("only S04 §5's edges and the documented exceptions").isSubsetOf(allowed);
        var expected = new HashSet<>(allowed); expected.remove("PAID → REFUNDED");
        assertThat(produced).as("every edge but S12's refund, and every documented exception, is really written").containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * INC-39.2 (ruling E149, E11-T17), S04 §5: the rows {@link UpfrontPayments#toCharge} writes for a PARTIAL row — the row
     * `CANCELLED` with its amounts, provider and cash `paidAt`; a `PAID` correction with the cash (provider, `paidAt`, amount and
     * its S12 channel and reference); a new `DUE` row for the rest under the same dog, concept and submission — and the rows it
     * returns, in the order asked. No event: the cash part was reported when it was received.
     */
    @Test void T_04_22_INC_39_aPartialRowToChargeIsClosedWithItsCashAndItsRestIsANewDueRow() {
        Instant cashAt = NOW.minusSeconds(7200);
        var partial = new UpfrontPayment("t-partial", CLUB, MEMBER, "dog-1", "OTHER", "ADDITIONAL_DOG_FEE", eur(6000), eur(4000), "PARTIAL", "MANUAL", null,
                NOW.minusSeconds(86400), cashAt, null, "sub-1", null, null, null, null, "BIZUM", "Fictional ref 7", List.of(), null);
        when(repository.member(MEMBER)).thenReturn(List.of(partial, row("t-due", "ENTRY_FEE", "dog-1", 10000, 0, "DUE", null, NOW, null, "sub-1")));
        List<UpfrontPayment> charged;
        try (var tenant = TenantContext.open(CLUB)) { charged = payments.toCharge(MEMBER, List.of("t-partial", "t-due")); }
        var updated = ArgumentCaptor.forClass(UpfrontPayment.class); verify(repository).update(updated.capture());
        assertThat(updated.getValue()).extracting(UpfrontPayment::id, UpfrontPayment::status, p -> p.amountDue().amountMinor(), p -> p.amountPaid().amountMinor(),
                UpfrontPayment::provider, UpfrontPayment::paidAt).containsExactly("t-partial", "CANCELLED", 6000L, 4000L, "MANUAL", cashAt);
        var inserted = ArgumentCaptor.forClass(UpfrontPayment.class); verify(repository, times(2)).insert(inserted.capture());
        var correction = inserted.getAllValues().get(0); var rest = inserted.getAllValues().get(1);
        assertThat(correction).extracting(UpfrontPayment::status, p -> p.amountDue().amountMinor(), p -> p.amountPaid().amountMinor(), UpfrontPayment::provider,
                UpfrontPayment::paidAt, UpfrontPayment::correctionOf, UpfrontPayment::channel, UpfrontPayment::reference)
                .containsExactly("PAID", 4000L, 4000L, "MANUAL", cashAt, "t-partial", "BIZUM", "Fictional ref 7");
        assertThat(rest).extracting(UpfrontPayment::status, p -> p.amountDue().amountMinor(), p -> p.amountPaid().amountMinor(), UpfrontPayment::provider,
                UpfrontPayment::paidAt, UpfrontPayment::correctionOf, UpfrontPayment::checkoutSessionId).containsExactly("DUE", 2000L, 0L, null, null, null, null);
        assertThat(rest).extracting(UpfrontPayment::clubId, UpfrontPayment::memberId, UpfrontPayment::dogId, UpfrontPayment::concept, UpfrontPayment::signupConcept,
                UpfrontPayment::submissionId, UpfrontPayment::createdAt).containsExactly(CLUB, MEMBER, "dog-1", "OTHER", "ADDITIONAL_DOG_FEE", "sub-1", NOW);
        assertThat(rest.id()).isNotIn("t-partial", correction.id());
        assertThat(charged).extracting(UpfrontPayment::id).containsExactly(rest.id(), "t-due");
        verifyNoInteractions(events);
        try (var tenant = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> payments.toCharge(MEMBER, List.of("t-missing"))).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.code()).isEqualTo(ErrorCode.INVALID_STATE));
        }
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static Money eur(long amount) { return new Money(amount, "EUR"); }

    static UpfrontPayment row(String id, String concept, String dogId, long due, long paid, String status, String session, Instant createdAt,
            String bookingId, String submissionId) {
        return new UpfrontPayment(id, CLUB, MEMBER, dogId, concept, concept, eur(due), eur(paid), status, null, session, createdAt, null, bookingId,
                submissionId, null);
    }
}

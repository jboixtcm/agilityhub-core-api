package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.payments.domain.PaymentIntentLedger.Reservation;
import com.agilityhub.core.payments.domain.PaymentIntentLedger.Row;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PaymentIntentLedgerTest {
    @Test void T_12_17_e8t12_round4_point1_absorptionUsesAllCreditsRegardlessOfSettlementPlacement() {
        for (long onFirst : List.of(0L, 500L, 1000L)) {
            var ledger = new PaymentIntentLedger(List.of(new Row("A", 1200, onFirst, 700, false),
                    new Row("B", 1200, 1000 - onFirst, 1200, true)), List.of());
            assertThat(ledger.creditReductions()).containsExactlyEntriesOf(Map.of("A", 500L));
            assertThat(ledger.overpaid()).isEqualTo(500);
            assertThat(ledger.freeFor("A")).isLessThanOrEqualTo(-500);
        }
    }

    @Test void T_12_17_e8t12_round4_point1_pendingReservationsDoNotBecomeSettledOverpayment() {
        var rows = List.of(new Row("A", 1200, 0, 700, false), new Row("B", 1200, 500, 1200, true));
        var ledger = new PaymentIntentLedger(rows, List.of(new Reservation("dashboard", null, 500)));
        assertThat(ledger.creditReductions()).containsExactlyEntriesOf(Map.of("A", 500L));
        assertThat(ledger.overpaid()).isZero();
        assertThat(ledger.settlementShares(500, null, null)).containsEntry("A", 500L);
        // Local commands prefer their own row but do not compete with their own reservation when settling.
        var local = new PaymentIntentLedger(List.of(new Row("A", 1200, 0, 0, false), new Row("B", 1200, 0, 0, false)),
                List.of(new Reservation("local", "B", 1200), new Reservation("dashboard", null, 1200)));
        assertThat(local.settlementShares(1200, "B", "local")).containsEntry("B", 1200L).containsEntry("A", 0L);
        assertThat(local.freeFor("A")).isZero();
    }

    @Test void T_12_17_e8t12_round4_point2_generatedLedgersConserveMoneyAcrossThreeRows() {
        var random = new Random(105);
        for (int sample = 0; sample < 2000; sample++) {
            var rows = new ArrayList<Row>();
            for (String id : List.of("A", "B", "C")) {
                rows.add(new Row(id, 1200, random.nextInt(1201), random.nextInt(1201), random.nextBoolean()));
            }
            var reservations = List.of(new Reservation("local", "B", random.nextInt(2401)),
                    new Reservation("dashboard", null, random.nextInt(2401)));
            var ledger = new PaymentIntentLedger(rows, reservations);
            var reduced = new ArrayList<Row>();
            long removed = 0, mutable = 0;
            for (var row : rows) {
                long reduction = ledger.creditReductions().getOrDefault(row.id(), 0L);
                assertThat(reduction).isBetween(0L, row.billed() ? 0L : row.credited());
                removed += reduction;
                if (!row.billed()) { mutable += row.credited(); }
                reduced.add(new Row(row.id(), row.captured(), row.refunded(), row.credited() - reduction, row.billed()));
            }
            long required = Math.max(0, rows.stream().mapToLong(row -> row.refunded() + row.credited()).sum()
                    + reservations.stream().mapToLong(Reservation::amount).sum() - 3600);
            assertThat(removed).isEqualTo(Math.min(mutable, required));
            var after = new PaymentIntentLedger(reduced, reservations);
            assertThat(Math.min(mutable - removed, Math.max(0, -after.balance().free()))).isZero();
            assertThat(after.overpaid()).isLessThanOrEqualTo(ledger.overpaid());
            assertThat(rows.stream().mapToLong(row -> ledger.share(row.id()).reserved()).sum())
                    .isEqualTo(reservations.stream().mapToLong(Reservation::amount).sum());
            long left = 3600 - rows.stream().mapToLong(Row::refunded).sum();
            long amount = random.nextLong(left + 1);
            var shares = ledger.settlementShares(amount, sample % 2 == 0 ? "B" : null, sample % 3 == 0 ? "local" : null);
            assertThat(shares.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(amount);
            for (var row : rows) { assertThat(shares.get(row.id())).isBetween(0L, row.captured() - row.refunded()); }
        }
    }

    @Test void T_12_17_e8t12_round5_point1_excludingOwnInterventionPreservesOtherClaims() {
        var ledger = new PaymentIntentLedger(List.of(new Row("A", 1200, 0, 0, false),
                new Row("B", 1200, 500, 1200, true), new Row("C", 1200, 0, 0, false)),
                List.of(new Reservation("A", "A", 700), new Reservation("C", "C", 1200)));
        assertThat(ledger.balance().free()).isZero();
        assertThat(ledger.withoutReservation("A").freeFor("A")).isEqualTo(700);
        assertThat(ledger.withoutReservation("C").freeFor("C")).isEqualTo(1200);
        assertThat(ledger.withoutReservation("missing")).isEqualTo(ledger);
        assertThat(ledger.reservations()).hasSize(2);
        assertThat(ledger.overpaid()).isZero();
    }

    @Test void T_12_17_e8t12_round4_point1_settlementCannotCreateCapture() {
        var ledger = new PaymentIntentLedger(List.of(new Row("A", 1200, 500, 0, false)), List.of());
        assertThatThrownBy(() -> ledger.settlementShares(701, null, null)).isInstanceOf(ApiException.class).hasMessage("REFUND_EXCEEDS_PAID");
        assertThat(new PaymentIntentLedger(List.of(), List.of()).balance().consistent()).isTrue();
    }
}

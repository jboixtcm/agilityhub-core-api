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
                List.of(Reservation.intervention("A", 700), Reservation.intervention("C", 1200)), List.of("C", "A"));
        assertThat(ledger.balance().free()).isZero();
        assertThat(ledger.withoutReservation("A").freeFor("A")).isEqualTo(700);
        assertThat(ledger.withoutReservation("C").freeFor("C")).isEqualTo(1200);
        assertThat(ledger.withoutReservation("missing")).isEqualTo(ledger);
        assertThat(ledger.reservations()).hasSize(2);
        assertThat(ledger.overpaid()).isZero();
    }

    @Test void T_12_17_e8t12_round6_point1_dashboardRefundRepaysInterventionsFirst() {
        for (String owing : List.of("A", "B")) {
            String other = owing.equals("A") ? "B" : "A";
            var rows = List.of(new Row("A", 1200, 0, 0, false), new Row("B", 1200, 0, 0, false));
            var ledger = new PaymentIntentLedger(rows, List.of(Reservation.intervention(owing, 1200)), List.of(owing));
            // The review's case: the claim never steers a direct settlement to the row that owes nothing.
            assertThat(ledger.settlementShares(1200, null, null)).as("owing %s", owing).containsEntry(owing, 1200L).containsEntry(other, 0L);
            assertThat(ledger.settlementShares(500, null, null)).as("owing %s", owing).containsEntry(owing, 500L).containsEntry(other, 0L);
            var repaid = new PaymentIntentLedger(rows.stream().map(row -> row.id().equals(owing) ? new Row(owing, 1200, 500, 0, false) : row).toList(),
                    ledger.reservations(), ledger.interventions());
            assertThat(repaid.claims()).containsExactlyEntriesOf(Map.of(owing, 700L));
            // A pending dashboard refund is reserved on the row in intervention first, as if its claim were absent.
            var pending = new PaymentIntentLedger(rows, List.of(new Reservation("dashboard", null, 500), Reservation.intervention(owing, 1200)), List.of(owing));
            assertThat(pending.claims()).containsExactlyEntriesOf(Map.of(owing, 700L));
            assertThat(pending.withClaims(pending.claims()).share(other).reserved()).as("owing %s", owing).isZero();
            // A command keeps its own row: only an unlinked refund is steered to the interventions.
            var local = new PaymentIntentLedger(rows, List.of(new Reservation("local", other, 500), Reservation.intervention(owing, 1200)), List.of(owing));
            assertThat(local.settlementShares(500, other, "local")).containsEntry(other, 500L).containsEntry(owing, 0L);
        }
    }

    /** Rows A, B (a billed 12 EUR credit and 5 EUR refunded) and C: the round-5 review's three-row capture. */
    private static List<Row> threeRows(long refundedA, long refundedC) {
        return List.of(new Row("A", 1200, refundedA, 0, false), new Row("B", 1200, 500, 1200, true), new Row("C", 1200, refundedC, 0, false));
    }

    @Test void T_12_17_e8t12_round6_point3_claimsAreRefreshedOnceInTheSplitOrder() {
        // C failed first: it holds 12 EUR and A the 7 EUR left. A 6 EUR dashboard refund repays C, A keeps its claim.
        var ledger = new PaymentIntentLedger(threeRows(0, 0), List.of(Reservation.intervention("A", 700), Reservation.intervention("C", 1200)), List.of("C", "A"));
        assertThat(ledger.claims()).containsExactly(entry("C", 1200L), entry("A", 700L));
        assertThat(ledger.settlementShares(600, null, null)).containsEntry("C", 600L).containsEntry("A", 0L).containsEntry("B", 0L);
        var repaid = new PaymentIntentLedger(threeRows(0, 600), ledger.reservations(), ledger.interventions());
        assertThat(repaid.claims()).containsExactly(entry("C", 600L), entry("A", 700L));
        // A failed first: the same refund repays A.
        var first = new PaymentIntentLedger(threeRows(0, 0), List.of(Reservation.intervention("A", 1200), Reservation.intervention("C", 700)), List.of("A", "C"));
        assertThat(first.settlementShares(600, null, null)).containsEntry("A", 600L).containsEntry("C", 0L);
        // An admin's repayment of A replaces A's claim, never C's earlier one, whatever the stale claims say.
        var command = new PaymentIntentLedger(threeRows(0, 300), List.of(new Reservation("repay", "A", 700),
                Reservation.intervention("A", 700), Reservation.intervention("C", 900)), List.of("C", "A"));
        assertThat(command.claims()).containsExactly(entry("C", 900L), entry("A", 0L));
        var stale = new PaymentIntentLedger(threeRows(0, 300), List.of(new Reservation("repay", "A", 700),
                Reservation.intervention("A", 1200), Reservation.intervention("C", 100)), List.of("C", "A"));
        assertThat(stale.claims()).isEqualTo(command.claims());
        // Refreshing the refreshed view changes nothing.
        assertThat(command.withClaims(command.claims()).claims()).isEqualTo(command.claims());
    }

    @Test void T_12_17_e8t12_round6_point2_generatedClaimsEqualTheirRecomputedDue() {
        var random = new Random(111);
        var ids = List.of("A", "B", "C");
        for (int sample = 0; sample < 3000; sample++) {
            var rows = new ArrayList<Row>();
            for (String id : ids) { rows.add(new Row(id, 1200, random.nextInt(1201), random.nextInt(4) == 0 ? random.nextInt(1201) : 0, random.nextBoolean())); }
            var order = new ArrayList<>(ids.stream().filter(id -> random.nextBoolean()).toList());
            Collections.shuffle(order, random);
            var reservations = new ArrayList<Reservation>();
            if (random.nextBoolean()) { reservations.add(new Reservation("local", ids.get(random.nextInt(3)), random.nextInt(1201))); }
            if (random.nextBoolean()) { reservations.add(new Reservation("dashboard", null, random.nextInt(1201))); }
            for (String id : order) { if (random.nextBoolean()) { reservations.add(Reservation.intervention(id, 1 + random.nextInt(1200))); } }
            var ledger = new PaymentIntentLedger(rows, reservations, order);
            var claims = ledger.claims();
            assertThat(claims.keySet()).as("sample %s", sample).containsExactlyElementsOf(order);
            var refreshed = ledger.withClaims(claims);
            for (String id : order) {
                // Lower bound and upper bound at once: neither a stale nor an excessive claim survives the refresh.
                assertThat(claims.get(id)).as("sample %s, row %s: %s", sample, id, ledger).isEqualTo(Math.max(0, refreshed.withoutReservation(id).freeFor(id)));
            }
            // INC-58 (E11-T02): an oracle of its own, not a fixed point of the assignment. In the split order, each row owes the
            // smaller of its own free share and the intent's free money left, both without any claim, and that is subtracted.
            var unclaimedLedger = ledger.withClaims(order.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> 0L)));
            long left = unclaimedLedger.balance().free();
            for (String id : order) {
                long owed = Math.max(0, Math.min(unclaimedLedger.share(id).free(), left));
                assertThat(claims.get(id)).as("oracle, sample %s, row %s: %s", sample, id, ledger).isEqualTo(owed);
                left -= owed;
            }
            long unclaimed = ledger.withClaims(order.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> 0L))).balance().free();
            assertThat(claims.values().stream().mapToLong(Long::longValue).sum()).as("sample %s", sample).isLessThanOrEqualTo(Math.max(0, unclaimed));
            assertThat(refreshed.claims()).as("sample %s", sample).isEqualTo(claims);
        }
    }

    @Test void T_12_17_e8t12_round4_point1_settlementCannotCreateCapture() {
        var ledger = new PaymentIntentLedger(List.of(new Row("A", 1200, 500, 0, false)), List.of());
        assertThatThrownBy(() -> ledger.settlementShares(701, null, null)).isInstanceOf(ApiException.class).hasMessage("REFUND_EXCEEDS_PAID");
        assertThat(new PaymentIntentLedger(List.of(), List.of()).balance().consistent()).isTrue();
    }
}

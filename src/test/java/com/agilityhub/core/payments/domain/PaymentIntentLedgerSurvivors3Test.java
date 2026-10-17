package com.agilityhub.core.payments.domain;

import com.agilityhub.core.payments.domain.PaymentIntentLedger.Reservation;
import com.agilityhub.core.payments.domain.PaymentIntentLedger.Row;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT session-10 survivors of {@link PaymentIntentLedger} (S12 R-12-20, E105/E111; T-12-17): a settlement takes every
 * row's uncredited funds first, then unbilled credit, then billed credit (each tier across all rows before the next), and an
 * intervention that owes nothing holds no claim. Amounts in minor units; fictional row ids.
 */
class PaymentIntentLedgerSurvivors3Test {
    @Test void T_12_17_aSettlementTakesEveryRowsUncreditedFundsBeforeAnyCredit() {
        // A: 100 captured, 50 of them a credit; B: 100 captured, no credit. 120 to settle → A's 50 free, then 70 of B's.
        var ledger = new PaymentIntentLedger(List.of(new Row("A", 100, 0, 50, false), new Row("B", 100, 0, 0, false)), List.of());

        assertThat(ledger.settlementShares(120, null, "operation-1")).isEqualTo(Map.of("A", 50L, "B", 70L));
    }

    @Test void T_12_17_aSettlementTakesUnbilledCreditBeforeBilledCredit() {
        // Both rows are wholly credited; A's credit is billed (immutable), B's is not: the 50 come out of B.
        var ledger = new PaymentIntentLedger(List.of(new Row("A", 100, 0, 100, true), new Row("B", 100, 0, 100, false)), List.of());

        assertThat(ledger.settlementShares(50, null, "operation-1")).isEqualTo(Map.of("A", 0L, "B", 50L));
    }

    @Test void T_12_17_anInterventionOwingNothingHoldsNoClaim() {
        var rows = List.of(new Row("A", 100, 0, 0, false));

        assertThat(new PaymentIntentLedger(rows, List.of(), List.of("A")).withClaims(Map.of("A", 0L)).reservations()).isEmpty();
        // A stale claim is dropped, not replaced by a zero one.
        var stale = new PaymentIntentLedger(rows, List.of(Reservation.intervention("A", 30)), List.of("A"));
        assertThat(stale.withClaims(Map.of("A", 0L)).reservations()).isEmpty();
        assertThat(stale.withClaims(Map.of("A", 20L)).reservations()).containsExactly(Reservation.intervention("A", 20));
    }
}

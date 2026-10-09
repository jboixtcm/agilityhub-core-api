package com.agilityhub.core.payments.domain;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E8-T09 round 5 (ruling E97): the ledger arithmetic of a captured payment, without Spring or Mongo. */
class CapturedPaymentLedgerTest {
    @Test void T_12_17_round5_freeIsWhatIsNeitherRefundedReservedNorCredited() {
        var ledger = new CapturedPaymentLedger(1200, 200, 300, 500);
        assertThat(ledger.free()).isEqualTo(200);
        assertThat(ledger.due()).isEqualTo(200);
        assertThat(ledger.consistent()).isTrue();
        assertThat(new CapturedPaymentLedger(1200, 200, 0, 1000).free()).isZero();
    }

    @Test void T_12_17_round5_anOvercommittedOrNegativeLedgerIsInconsistentAndOwesNothing() {
        var over = new CapturedPaymentLedger(1200, 1000, 0, 1000);
        assertThat(over.consistent()).isFalse();
        assertThat(over.free()).isEqualTo(-800);
        assertThat(over.due()).isZero();
        assertThat(new CapturedPaymentLedger(-1, 0, 0, 0).consistent()).isFalse();
        assertThat(new CapturedPaymentLedger(1200, -1, 0, 0).consistent()).isFalse();
        assertThat(new CapturedPaymentLedger(1200, 0, -1, 0).consistent()).isFalse();
        assertThat(new CapturedPaymentLedger(1200, 0, 0, -1).consistent()).isFalse();
    }

    @Test void T_12_17_round5_generatedLedgersConserveTheCapture() {
        var random = new Random(97);
        for (int i = 0; i < 1000; i++) {
            long captured = random.nextInt(5000), refunded = random.nextInt(5000), reserved = random.nextInt(5000), credited = random.nextInt(5000);
            var ledger = new CapturedPaymentLedger(captured, refunded, reserved, credited);
            assertThat(ledger.refunded() + ledger.reserved() + ledger.credited() + ledger.free()).isEqualTo(captured);
            assertThat(ledger.consistent()).isEqualTo(refunded + reserved + credited <= captured);
            assertThat(ledger.due()).isEqualTo(Math.max(0, ledger.free()));
        }
    }
}

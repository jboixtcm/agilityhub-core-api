package com.agilityhub.core.clubs.bookings.application.ports;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link PackBalancePort} (S08 R-08-17; T-08-11): an adapter that only knows the undated
 * operations still answers the dated ones, with its own balance and its own movement id.
 */
class PackBalancePortSurvivors3Test {
    static final LocalDate CLASS_DATE = LocalDate.parse("2026-10-07");

    /** A pack adapter without dated reads: Duna's 10-session pack with 3 consumed; every consumption returns its movement. */
    static final class UndatedPacks implements PackBalancePort {
        final List<String> calls = new ArrayList<>();
        @Override public Optional<Balance> balance(String memberId, String dogId) {
            calls.add("balance " + memberId + " " + dogId);
            return Optional.of(new Balance("pack-1", 10, 3, 7, LocalDate.parse("2026-12-31")));
        }
        @Override public String consume(String memberId, String dogId, String bookingId) {
            calls.add("consume " + memberId + " " + dogId + " " + bookingId);
            return "movement-" + bookingId;
        }
        @Override public String refund(String memberId, String dogId, String bookingId, LocalDate today) { return null; }
    }

    @Test void T_08_11_theDatedBalanceOfAnUndatedAdapterIsItsBalance() {
        var packs = new UndatedPacks();

        assertThat(packs.balance("member-laura", "dog-duna", CLASS_DATE))
                .contains(new PackBalancePort.Balance("pack-1", 10, 3, 7, LocalDate.parse("2026-12-31")));
        assertThat(packs.calls).containsExactly("balance member-laura dog-duna");
    }

    @Test void T_08_11_theDatedConsumptionOfAnUndatedAdapterReturnsItsMovement() {
        var packs = new UndatedPacks();

        assertThat(packs.consume("member-laura", "dog-duna", "booking-1", CLASS_DATE)).isEqualTo("movement-booking-1");
        assertThat(packs.calls).containsExactly("consume member-laura dog-duna booking-1");
    }
}

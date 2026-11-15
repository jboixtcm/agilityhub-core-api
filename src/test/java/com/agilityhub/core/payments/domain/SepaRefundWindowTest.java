package com.agilityhub.core.payments.domain;

import java.time.LocalDate;
import java.time.Period;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** PRIV-04 (owner's decision of 13-11-2026): the SEPA Core refund window of 8 weeks after the debit date. */
class SepaRefundWindowTest {
    @Test void PRIV_04_aDebitIsRefundableForEightWeeksAndTheWindowClosesTheDayAfter() {
        assertThat(SepaRefundWindow.LENGTH).isEqualTo(Period.ofWeeks(8));
        LocalDate debited = LocalDate.of(2026, 9, 1);
        assertThat(SepaRefundWindow.closesOn(debited)).isEqualTo(LocalDate.of(2026, 10, 28));
        // On the last day of the window the debit is still inside it; the day it closes it is not.
        assertThat(SepaRefundWindow.openSince(LocalDate.of(2026, 10, 27))).isEqualTo(debited);
        assertThat(SepaRefundWindow.openSince(LocalDate.of(2026, 10, 28))).isAfter(debited).isEqualTo(LocalDate.of(2026, 9, 2));
        // Across a month end and a leap day.
        assertThat(SepaRefundWindow.closesOn(LocalDate.of(2028, 1, 31))).isEqualTo(LocalDate.of(2028, 3, 28));
        assertThat(SepaRefundWindow.openSince(LocalDate.of(2028, 3, 27))).isEqualTo(LocalDate.of(2028, 1, 31));
    }
}

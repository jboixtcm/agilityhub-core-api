package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** T-12-06 (R-12-12): the pain.008 decisions without XML — texts, identifiers, sequence types, blocks and totals. */
class SepaDirectDebitsTest {

    @Test void T_12_06_textsAreInTheSepaCharacterSetAndCutOnACharacterBoundary() {
        assertThat(SepaText.of("Cànic · Núria", 140)).isEqualTo("Canic - Nuria");
        assertThat(SepaText.of("Quota Abonat — Setembre 2026", 140)).isEqualTo("Quota Abonat Setembre 2026");
        assertThat(SepaText.of("Col·legi «Ñandú» ŀl Çà & ß 50%", 140)).isEqualTo("Col-legi Nandu l-l Ca 50");
        assertThat(SepaText.of("  Joan   O'Brien (Jr.) +34/1:2? ", 140)).isEqualTo("Joan O'Brien (Jr.) +34/1:2?");
        assertThat(SepaText.of(null, 140)).isEmpty();
        assertThat(SepaText.of("Дуна", 140)).isEmpty();
        String longer = "Àlex ".repeat(40);
        assertThat(SepaText.of(longer, 140)).hasSize(139).doesNotEndWith(" ").matches("[A-Za-z ]+");
        assertThat(SepaText.of("a".repeat(141), 140)).hasSize(140);
        assertThat(SepaText.of("Laura Serra Puig", SepaText.NAME)).isEqualTo("Laura Serra Puig");
    }

    @Test void T_12_06_messageAndBlockIdentifiersStayWithin35AndKeepTheirSequence() {
        assertThat(SepaDirectDebits.messageId("canic", "2026-09", 1)).isEqualTo("canic-2026-09-1");
        assertThat(SepaDirectDebits.messageId("club-d-agility-canic-del-valles-oriental", "2026-09", 1234)).hasSize(35).endsWith("-2026-09-1234")
                .doesNotContain("--");
        assertThat(SepaDirectDebits.messageId("cànic del vallès", "2026-10", 2)).isEqualTo("canic-del-valles-2026-10-2");
        assertThat(SepaDirectDebits.paymentInformationId("canic", "2026-09", 1, SepaDirectDebits.SequenceType.FRST)).isEqualTo("canic-2026-09-1-FRST");
        // A slug with nothing left in the SEPA set leaves the identifier to its own parts.
        assertThat(SepaDirectDebits.messageId("···", "2026-09", 1)).isEqualTo("2026-09-1");
    }

    @Test void T_12_06_frstOnlyForANewMandateWithUseFrst() {
        assertThat(SepaDirectDebits.sequence(true, false)).isEqualTo(SepaDirectDebits.SequenceType.FRST);
        assertThat(SepaDirectDebits.sequence(true, true)).isEqualTo(SepaDirectDebits.SequenceType.RCUR);
        assertThat(SepaDirectDebits.sequence(false, false)).isEqualTo(SepaDirectDebits.SequenceType.RCUR);
        assertThat(SepaDirectDebits.sequence(false, true)).isEqualTo(SepaDirectDebits.SequenceType.RCUR);
    }

    @Test void R_12_12_oneBlockPerSequenceTypeFrstFirstWithCountsAndTotalsInEuros() {
        var file = SepaDirectDebits.file("canic", "2026-09", 1, LocalDateTime.of(2026, 8, 25, 10, 0, 7, 123_000_000), creditor(), LocalDate.of(2026, 9, 1),
                List.of(debit("2026-0912", 6000, SepaDirectDebits.SequenceType.RCUR), debit("2026-0913", 9000, SepaDirectDebits.SequenceType.FRST),
                        debit("2026-0914", 1, SepaDirectDebits.SequenceType.RCUR)));
        assertThat(file.blocks()).extracting(SepaDirectDebits.Block::sequence).containsExactly(SepaDirectDebits.SequenceType.FRST, SepaDirectDebits.SequenceType.RCUR);
        assertThat(file.blocks().get(1).debits()).extracting(SepaDirectDebits.Debit::endToEndId).containsExactly("2026-0912", "2026-0914");
        assertThat(file.blocks().get(1).total()).isEqualByComparingTo("60.01");
        assertThat(file.total()).isEqualTo(new BigDecimal("150.01"));
        assertThat(file.count()).isEqualTo(3);
        assertThat(file.count(SepaDirectDebits.SequenceType.FRST)).isEqualTo(1);
        assertThat(file.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 25, 10, 0, 7));
        assertThat(SepaDirectDebits.file("canic", "2026-09", 1, LocalDateTime.of(2026, 8, 25, 10, 0), creditor(), LocalDate.of(2026, 9, 1),
                List.of(debit("2026-0912", 6000, SepaDirectDebits.SequenceType.RCUR))).blocks()).singleElement()
                .extracting(SepaDirectDebits.Block::paymentInformationId).isEqualTo("canic-2026-09-1-RCUR");
        assertThatThrownBy(() -> SepaDirectDebits.euros(new Money(100, "USD"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void R_12_12_theCreditorIdentifierCarriesTheSuffixAsItsBusinessCode() {
        assertThat(SepaDirectDebits.creditorIdentifier("ES12ZZZB12345678", "001")).isEqualTo("ES12001B12345678");
        assertThat(SepaDirectDebits.creditorIdentifier("es12 zzz b12345678", null)).isEqualTo("ES12ZZZB12345678");
        assertThat(SepaDirectDebits.creditorIdentifier("ES12ZZZB12345678", " ")).isEqualTo("ES12ZZZB12345678");
        assertThat(SepaDirectDebits.creditorIdentifier("ES12", "001")).isEqualTo("ES12");
        assertThat(SepaDirectDebits.creditorIdentifier(null, "001")).isNull();
        assertThat(SepaDirectDebits.compactIban("es00 0000 0000 0000 0000 1234")).isEqualTo("ES0000000000000000001234");
        assertThat(SepaDirectDebits.compactIban(null)).isNull();
    }

    static SepaDirectDebits.Creditor creditor() { return new SepaDirectDebits.Creditor("Club", "ES00ZZZG00000000", "ES0000000000000000009876", null); }
    static SepaDirectDebits.Debit debit(String number, long cents, SepaDirectDebits.SequenceType sequence) {
        return new SepaDirectDebits.Debit(number, new Money(cents, "EUR"), "canic-1-1", LocalDate.of(2026, 7, 15), "Titular", "ES0000000000000000000001", "Quota",
                sequence);
    }
}

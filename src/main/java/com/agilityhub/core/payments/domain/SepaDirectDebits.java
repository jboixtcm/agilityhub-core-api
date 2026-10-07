package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * S12 R-12-12 (E8-T03): the content of a remittance's pain.008 file, decided without XML, Spring or Mongo — the identifiers
 * (`MsgId`, `PmtInfId`), the sequence type of each debit, the `PmtInf` blocks and their totals. `SepaRemittanceWriter`
 * gathers the inputs and `Pain008Document` writes them.
 *
 * <ul>
 * <li>`MsgId = {clubSlug}-{period}-{seq}` and `PmtInfId = {clubSlug}-{period}-{seq}-{FRST|RCUR}`: at most 35 characters, the
 * slug cut to fit (never the sequence, which keeps `{clubId, messageId}` unique).</li>
 * <li>One block per sequence type, `FRST` first: `FRST` only with `billing.sepa.useFrst` and for a mandate never collected
 * before; otherwise everything is `RCUR` (the Cànic: Josep 08-09, B5/B6).</li>
 * <li>Amounts come from the collections, in euros with two decimals (a SEPA debit is in euros, R-12-12).</li>
 * </ul>
 */
public final class SepaDirectDebits {
    private SepaDirectDebits() { }
    /** `MsgId`, `PmtInfId`, `EndToEndId`, `MndtId`: `Max35Text`. */
    public static final int IDENTIFIER = 35;
    public static final String CURRENCY = "EUR";

    public enum SequenceType { FRST, RCUR }

    /** The club as creditor (`CLUB.paymentProviders.SEPA_XML`); `identifier` is the scheme identifier with its suffix. */
    public record Creditor(String name, String identifier, String iban, String bic) { }
    /** One collection to debit. `remittanceInformation` is the frozen description, still unformatted. */
    public record Debit(String endToEndId, Money amount, String mandateRef, LocalDate mandateSignedAt, String debtorName, String debtorIban,
            String remittanceInformation, SequenceType sequence) { }
    /** A `PmtInf`: the debits of one sequence type. */
    public record Block(String paymentInformationId, SequenceType sequence, List<Debit> debits, BigDecimal total) { }
    /** The file: `GrpHdr` (`messageId`, club-local `createdAt`, `count`, `total`) and its blocks. */
    public record File(String messageId, LocalDateTime createdAt, Creditor creditor, LocalDate collectionDate, List<Block> blocks, int count,
            BigDecimal total) {
        public int count(SequenceType sequence) {
            return blocks.stream().filter(block -> block.sequence() == sequence).mapToInt(block -> block.debits().size()).sum();
        }
    }

    /** R-12-12: `FRST` only when the club asks for it and the mandate was never collected (submitted or succeeded) before. */
    public static SequenceType sequence(boolean useFrst, boolean collectedBefore) {
        return useFrst && !collectedBefore ? SequenceType.FRST : SequenceType.RCUR;
    }

    /** `{clubSlug}-{period}-{seq}`, the slug cut so that the whole is at most 35 characters. */
    public static String messageId(String clubSlug, String period, long sequence) {
        return identifier(clubSlug, "-" + period + "-" + sequence);
    }

    /** The `PmtInfId` of a block: the message's parts and the sequence type, at most 35 characters. */
    public static String paymentInformationId(String clubSlug, String period, long sequence, SequenceType type) {
        return identifier(clubSlug, "-" + period + "-" + sequence + "-" + type.name());
    }

    /**
     * The creditor scheme identifier (AT-02) with the club's `suffix`: the creditor business code is characters 5–7 of the
     * identifier (`ES12ZZZB12345678` with suffix `001` → `ES12001B12345678`); the check digits do not cover it. No suffix →
     * the identifier as configured.
     */
    public static String creditorIdentifier(String creditorId, String suffix) {
        String id = creditorId == null ? null : creditorId.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
        if (id == null || suffix == null || suffix.isBlank() || id.length() < 8) { return id; }
        return id.substring(0, 4) + suffix.toUpperCase(java.util.Locale.ROOT) + id.substring(7);
    }

    /** An IBAN as the file writes it: no spaces, upper case. */
    public static String compactIban(String iban) {
        return iban == null ? null : iban.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
    }

    /** A collection amount in euros with two decimals ({@code 6000} → `60.00`); anything but euros is `CURRENCY_MISMATCH`. */
    public static BigDecimal euros(Money amount) {
        if (!CURRENCY.equals(amount.currency())) { throw new IllegalArgumentException("A SEPA debit is in euros"); }
        return BigDecimal.valueOf(amount.amountMinor(), 2);
    }

    /** The file of {@code debits} (in the run's order): one block per sequence type, `FRST` first, with their counts and totals. */
    public static File file(String clubSlug, String period, long sequence, LocalDateTime createdAt, Creditor creditor, LocalDate collectionDate,
            List<Debit> debits) {
        var blocks = new ArrayList<Block>();
        for (var type : SequenceType.values()) {
            var of = debits.stream().filter(debit -> debit.sequence() == type).toList();
            if (!of.isEmpty()) { blocks.add(new Block(paymentInformationId(clubSlug, period, sequence, type), type, of, total(of))); }
        }
        return new File(messageId(clubSlug, period, sequence), createdAt.withNano(0), creditor, collectionDate, List.copyOf(blocks), debits.size(),
                total(debits));
    }

    private static BigDecimal total(List<Debit> debits) {
        return debits.stream().map(debit -> euros(debit.amount())).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private static String identifier(String clubSlug, String suffix) {
        String slug = SepaText.of(clubSlug, IDENTIFIER).replace(' ', '-');
        int room = Math.max(0, IDENTIFIER - suffix.length());
        String head = slug.substring(0, Math.min(slug.length(), room)).replaceAll("-+$", "");
        return head.isEmpty() ? suffix.substring(1) : head + suffix;
    }
}

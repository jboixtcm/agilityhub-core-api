package com.agilityhub.core.payments.application.sepa;

import com.agilityhub.core.payments.application.BankAccountVault;
import com.agilityhub.core.payments.application.ports.RemittanceWriterPort;
import com.agilityhub.core.payments.domain.CollectionDates;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.domain.RemittanceStatus;
import com.agilityhub.core.payments.domain.SepaDirectDebits;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.payments.persistence.BillingRun;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.Remittance;
import com.agilityhub.core.platform.application.BillingProviderSettings;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.ExportFileStore;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * S12 R-12-11/12 (E8-T03, WP-12-C): the run's `SEPA_XML` collections as a pain.008 file, written inside the run's transaction.
 *
 * <ol>
 * <li>Fail fast: the club's `SEPA_XML` creditor must be configured (name, identifier, IBAN) and `billing.sepa.schema` known —
 * else `422 SEPA_NOT_CONFIGURED`; the club and every amount in euros — else `422 CURRENCY_MISMATCH`; the collection date at
 * least two business days after today (club-local) — else `422 COLLECTION_DATE_TOO_SOON {requested, earliest}`.</li>
 * <li>Each debit takes its amount, `EndToEndId`, mandate and signature from its collection (E90), and the frozen description
 * from the invoice. The current holder and full IBAN come from the member through {@link BankAccountVault}. Legacy
 * collections without a signature use the current mandate's signature. A missing current reference, signature or account
 * fails with `422 SEPA_NOT_CONFIGURED {memberIds}`. Waiting receipts use the new collection's current mandate.</li>
 * <li>`FRST` only with `billing.sepa.useFrst` and for a mandate never submitted or collected before (a debit of a remittance
 * marked as sent counts); otherwise `RCUR`.</li>
 * <li>The bytes are validated against the schema before anything is stored; a failure is `422 SEPA_NOT_CONFIGURED {reason:
 * SCHEMA}` (logged with its position only). Then the file goes to the export store under
 * `remittances/{clubId}/{period}/{messageId}-{remittanceId}.xml` — outside Mongo, so a storage failure aborts the run, and if
 * the run's transaction rolls back afterwards its file is deleted. An unknown result checks the remittance in a fresh
 * transaction; an absent row deletes the file, and an unavailable check retains it with a WARN key and trace id.</li>
 * </ol>
 */
@Component
public class SepaRemittanceWriter implements RemittanceWriterPort {
    private static final Logger log = LoggerFactory.getLogger(SepaRemittanceWriter.class);
    static final String CONTENT_TYPE = "application/xml";
    private final RemittanceRepository remittances; private final InvoiceRepository invoices; private final CollectionRepository collections;
    private final ClubConfigService configs; private final BillingProviderSettings providers; private final BillingCensusAccess census;
    private final BankAccountVault vault; private final ExportFileStore files; private final Clock clock;
    private final TransactionTemplate committedRead;
    public SepaRemittanceWriter(RemittanceRepository remittances, InvoiceRepository invoices, CollectionRepository collections, ClubConfigService configs,
            BillingProviderSettings providers, BillingCensusAccess census, BankAccountVault vault, ExportFileStore files, Clock clock, PlatformTransactionManager manager) {
        this.remittances = remittances; this.invoices = invoices; this.collections = collections; this.configs = configs; this.providers = providers;
        this.census = census; this.vault = vault; this.files = files; this.clock = clock;
        this.committedRead = new TransactionTemplate(manager);
        this.committedRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public Remittance write(BillingRun run, String remittanceId, List<Collection> debits, LocalDate collectionDate) {
        if (debits.isEmpty()) { throw new IllegalArgumentException("A remittance needs at least one collection"); }
        String club = TenantContext.require();
        var config = configs.get(club);
        String schema = Objects.requireNonNullElse(config.get("billing.sepa.schema", String.class), Pain008Document.SCHEMA);
        if (!Pain008Document.SCHEMA.equals(schema)) { throw new ApiException(ErrorCode.SEPA_NOT_CONFIGURED, Map.of("reason", "SCHEMA")); }
        var creditor = providers.sepaCreditor().filter(BillingProviderSettings.SepaCreditor::configured)
                .orElseThrow(() -> new ApiException(ErrorCode.SEPA_NOT_CONFIGURED));
        if (!SepaDirectDebits.CURRENCY.equals(config.club().currency())
                || debits.stream().anyMatch(debit -> !SepaDirectDebits.CURRENCY.equals(debit.amount().currency()))) {
            throw new ApiException(ErrorCode.CURRENCY_MISMATCH);
        }
        ZoneId zone = ZoneId.of(config.club().timeZone());
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (CollectionDates.tooSoon(collectionDate, today)) {
            throw new ApiException(ErrorCode.COLLECTION_DATE_TOO_SOON, Map.of("requested", collectionDate.toString(),
                    "earliest", CollectionDates.earliest(today).toString()));
        }
        var lines = debits(club, zone, debits, Boolean.TRUE.equals(config.get("billing.sepa.useFrst", Boolean.class)));
        long sequence = remittances.forPeriod(run.period()).size() + 1L;
        var payee = new SepaDirectDebits.Creditor(creditor.name(), SepaDirectDebits.creditorIdentifier(creditor.id(), creditor.suffix()),
                SepaDirectDebits.compactIban(creditor.iban()), creditor.bic());
        var file = SepaDirectDebits.file(config.club().slug(), run.period(), sequence, LocalDateTime.ofInstant(now, zone), payee, collectionDate, lines);
        byte[] xml = Pain008Document.write(file);
        try { Pain008Document.validate(xml); }
        catch (Pain008Document.InvalidDocument invalid) {
            log.warn("Remittance {} of run {}: pain.008 invalid at line {}, column {}", file.messageId(), run.id(), invalid.line(), invalid.column());
            throw new ApiException(ErrorCode.SEPA_NOT_CONFIGURED, Map.of("reason", "SCHEMA"));
        }
        Instant validatedAt = clock.instant();
        String key = "remittances/" + club + "/" + run.period() + "/" + file.messageId() + "-" + remittanceId + ".xml";
        store(club, remittanceId, key, xml);
        long total = debits.stream().mapToLong(debit -> debit.amount().amountMinor()).sum();
        return new Remittance(remittanceId, club, run.id(), run.period(), file.messageId(), now, collectionDate.toString(),
                new Remittance.Creditor(creditor.name(), payee.identifier(), payee.iban(), creditor.bic()), debits.stream().map(Collection::id).toList(),
                debits.size(), new Money(total, SepaDirectDebits.CURRENCY),
                new Remittance.SequenceBreakdown(file.count(SepaDirectDebits.SequenceType.FRST), file.count(SepaDirectDebits.SequenceType.RCUR)), key,
                validatedAt, null, RemittanceStatus.GENERATED, null, null, null, now, run.createdByAccountId());
    }

    /** Each collection as a debit, in the run's order; the members whose mandate or account is incomplete fail together. */
    private List<SepaDirectDebits.Debit> debits(String club, ZoneId zone, List<Collection> debits, boolean useFrst) {
        var byId = invoices.forIds(debits.stream().map(Collection::invoiceId).toList()).stream()
                .collect(Collectors.toMap(Invoice::id, invoice -> invoice));
        var accounts = census.sepaAccounts(byId.values().stream().map(Invoice::memberId).collect(Collectors.toCollection(TreeSet::new)));
        Set<String> collected = useFrst ? collected(debits) : Set.of();
        var lines = new ArrayList<SepaDirectDebits.Debit>(); var incomplete = new TreeSet<String>();
        for (var debit : debits) {
            var invoice = byId.get(debit.invoiceId());
            if (invoice == null) { throw new IllegalStateException("Collection " + debit.id() + " has no invoice in the open club"); }
            var account = accounts.get(invoice.memberId());
            String mandate = debit.mandateRef();
            Instant signed = debit.mandateSignedAt() != null ? debit.mandateSignedAt() : account == null ? null : account.mandateSignedAt();
            String iban = account == null ? null : vault.resolve(account.account(), club, invoice.memberId());
            if (mandate == null || mandate.isBlank() || signed == null || iban == null || account.mandateRef() == null
                    || account.mandateRef().isBlank() || account.mandateSignedAt() == null) {
                incomplete.add(invoice.memberId()); continue;
            }
            String holder = firstText(account.holderName(), invoice.paymentMethod().holderName(), invoice.memberSnapshot().fullName());
            String description = invoice.lines().stream().map(Invoice.Line::description).filter(Objects::nonNull).collect(Collectors.joining(", "));
            lines.add(new SepaDirectDebits.Debit(debit.endToEndId(), debit.amount(), mandate, LocalDate.ofInstant(signed, zone), holder, iban, description,
                    SepaDirectDebits.sequence(useFrst, collected.contains(mandate))));
        }
        if (!incomplete.isEmpty()) { throw new ApiException(ErrorCode.SEPA_NOT_CONFIGURED, Map.of("memberIds", List.copyOf(incomplete))); }
        return lines;
    }

    /** R-12-12: the mandates among {@code debits}' with an earlier debit submitted or collected (`SUBMITTED`/`SUCCEEDED`, or sent with its remittance). */
    private Set<String> collected(List<Collection> debits) {
        var mandates = debits.stream().map(Collection::mandateRef).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        if (mandates.isEmpty()) { return Set.of(); }
        var history = collections.sepaAttempts(mandates);
        var sent = remittances.submittedAmong(history.stream().map(Collection::remittanceId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return history.stream().filter(attempt -> attempt.status() == CollectionStatus.SUBMITTED || attempt.status() == CollectionStatus.SUCCEEDED
                || sent.contains(attempt.remittanceId())).map(Collection::mandateRef).collect(Collectors.toSet());
    }

    /** The file goes to the store from a private temporary file; a rollback of the run afterwards deletes it again. */
    private void store(String club, String remittanceId, String key, byte[] xml) {
        try {
            var spool = Files.createTempFile("remittance-", ".xml");
            try { Files.write(spool, xml); files.put(key, spool, CONTENT_TYPE); }
            finally { Files.deleteIfExists(spool); }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            String traceId = org.slf4j.MDC.get("traceId");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_UNKNOWN) {
                        // The completed transaction's session can still be bound here. Read in a fresh transaction so an
                        // uncommitted row cannot be mistaken for proof of commit. An unavailable database keeps the file.
                        log.warn("Unknown remittance commit: storageKey={} traceId={}", key, traceId);
                        try (var tenant = TenantContext.open(club)) {
                            boolean persisted = Boolean.TRUE.equals(committedRead.execute(ignored -> remittances.findById(remittanceId).isPresent()));
                            if (persisted) { return; }
                        } catch (RuntimeException unavailable) {
                            log.warn("Remittance commit could not be checked: storageKey={} traceId={}", key, traceId);
                            return;
                        }
                    } else if (status != STATUS_ROLLED_BACK) { return; }
                    try { files.delete(key); }
                    catch (RuntimeException failure) { log.warn("Remittance file was not deleted: storageKey={} traceId={}", key, traceId); }
                }
            });
        }
    }

    private static String firstText(String... values) {
        for (String value : values) { if (value != null && !value.isBlank()) { return value; } }
        return "";
    }
}

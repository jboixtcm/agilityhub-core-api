package com.agilityhub.core.payments.application.sepa;

import com.agilityhub.core.payments.application.BankAccountVault;
import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.payments.persistence.BillingRun;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.Remittance;
import com.agilityhub.core.platform.application.BillingProviderSettings;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.ExportFileStore;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T-12-06 (R-12-12, E8-T03 step 5): `SepaRemittanceWriter` without Spring or Mongo — the repositories, the census, the club and
 * the store are mocks; the vault, the pain.008 classes and the schema are the real ones. Every file written here is validated
 * against the schema in use by the writer itself. Fictional people and `ES00` IBANs only.
 */
class SepaRemittanceWriterTest {
    static final String CLUB = "club-a", KEY = BankAccountVaultKey.KEY;
    static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");
    final RemittanceRepository remittances = mock(RemittanceRepository.class);
    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final CollectionRepository collections = mock(CollectionRepository.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final ClubConfig config = mock(ClubConfig.class);
    final ClubConfig.ClubView club = mock(ClubConfig.ClubView.class);
    final BillingProviderSettings providers = mock(BillingProviderSettings.class);
    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final BankAccountVault vault = new BankAccountVault(KEY);
    final ExportFileStore files = mock(ExportFileStore.class);
    final Map<String, String> stored = new LinkedHashMap<>();
    final Map<String, Invoice> issued = new LinkedHashMap<>();
    final Map<String, BillingCensusAccess.SepaAccount> accounts = new LinkedHashMap<>();
    SepaRemittanceWriter writer;
    AutoCloseable tenant;

    @BeforeEach void club() throws Exception {
        tenant = TenantContext.open(CLUB);
        when(configs.get(CLUB)).thenReturn(config);
        when(config.club()).thenReturn(club);
        when(club.slug()).thenReturn("canic"); when(club.currency()).thenReturn("EUR"); when(club.timeZone()).thenReturn("Europe/Madrid");
        when(config.get("billing.sepa.schema", String.class)).thenReturn("pain.008.001.02");
        when(config.get("billing.sepa.useFrst", Boolean.class)).thenReturn(false);
        when(providers.sepaCreditor()).thenReturn(Optional.of(new BillingProviderSettings.SepaCreditor("Club d'Agility Cànic", "ES00ZZZG00000000",
                "ES00 0000 0000 0000 0000 9876", null, "001")));
        when(remittances.forPeriod("2026-09")).thenReturn(List.of());
        when(invoices.forIds(any())).thenAnswer(call -> List.copyOf(issued.values()));
        when(census.sepaAccounts(any())).thenAnswer(call -> accounts);
        when(collections.sepaAttempts(any())).thenReturn(List.of());
        doAnswer(call -> { stored.put(call.getArgument(0), Files.readString(call.<Path>getArgument(1), StandardCharsets.UTF_8)); return null; })
                .when(files).put(anyString(), any(Path.class), anyString());
        writer = new SepaRemittanceWriter(remittances, invoices, collections, configs, providers, census, vault, files, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @AfterEach void close() throws Exception { tenant.close(); }

    @Test void T_12_06_withUseFrstOffTheCanicGetsOneRcurBlockWithItsCountAndTotal() {
        var debits = List.of(debit("m1", 1, 6000, "Quota Abonat — Setembre 2026"), debit("m2", 2, 9000, "Quota Abonat 2 gossos — Setembre 2026"));
        var remittance = writer.write(run(), "remittance-1", debits, LocalDate.of(2026, 9, 1));
        assertThat(remittance.sequenceBreakdown()).isEqualTo(new Remittance.SequenceBreakdown(0, 2));
        assertThat(remittance.count()).isEqualTo(2);
        assertThat(remittance.total()).isEqualTo(new Money(15000, "EUR"));
        assertThat(remittance.messageId()).isEqualTo("canic-2026-09-1");
        assertThat(remittance.xsdValidatedAt()).isEqualTo(NOW);
        assertThat(remittance.xsdValidationSkipped()).isNull();
        assertThat(remittance.fileKey()).isEqualTo("remittances/club-a/2026-09/canic-2026-09-1-remittance-1.xml");
        assertThat(remittance.creditor().id()).isEqualTo("ES00001G00000000");
        String xml = stored.get(remittance.fileKey());
        assertThat(xml.split("<PmtInf>", -1)).hasSize(2);
        assertThat(xml).contains("<SeqTp>RCUR</SeqTp>").doesNotContain("FRST").contains("<NbOfTxs>2</NbOfTxs>", "<CtrlSum>150.00</CtrlSum>",
                "<InstdAmt Ccy=\"EUR\">60.00</InstdAmt>", "<ReqdColltnDt>2026-09-01</ReqdColltnDt>", "<CreDtTm>2026-08-25T10:00:00</CreDtTm>",
                "<MndtId>canic-1-1</MndtId>", "<DtOfSgntr>2026-07-15</DtOfSgntr>", "<IBAN>ES0000000000000000000001</IBAN>",
                "<Ustrd>Quota Abonat Setembre 2026</Ustrd>", "<Nm>Club d'Agility Canic</Nm>", "<Id>NOTPROVIDED</Id>", "<Cd>CORE</Cd>", "<Cd>SEPA</Cd>");
        verify(files).put(eq(remittance.fileKey()), any(Path.class), eq("application/xml"));
    }

    @Test void T_12_06_withUseFrstOnANewMandateIsFrstAndOneAlreadySentIsRcur() {
        when(config.get("billing.sepa.useFrst", Boolean.class)).thenReturn(true);
        var debits = List.of(debit("m1", 1, 6000, "Quota"), debit("m2", 2, 6000, "Quota"), debit("m3", 3, 6000, "Quota"));
        // m1's mandate was in a remittance sent to the bank; m2's attempt was only created (its remittance never sent): still FRST.
        when(collections.sepaAttempts(any())).thenReturn(List.of(attempt("old-1", "canic-1-1", CollectionStatus.CREATED, "sent"),
                attempt("old-2", "canic-2-1", CollectionStatus.CREATED, "draft"), attempt("old-3", "canic-3-1", CollectionStatus.SUCCEEDED, null)));
        when(remittances.submittedAmong(any())).thenReturn(Set.of("sent"));
        var remittance = writer.write(run(), "remittance-2", debits, LocalDate.of(2026, 9, 1));
        assertThat(remittance.sequenceBreakdown()).isEqualTo(new Remittance.SequenceBreakdown(1, 2));
        String xml = stored.get(remittance.fileKey());
        assertThat(xml.split("<PmtInf>", -1)).hasSize(3);
        assertThat(xml.indexOf("<SeqTp>FRST</SeqTp>")).isLessThan(xml.indexOf("<SeqTp>RCUR</SeqTp>"));
        assertThat(xml).contains("<PmtInfId>canic-2026-09-1-FRST</PmtInfId>", "<PmtInfId>canic-2026-09-1-RCUR</PmtInfId>", "<NbOfTxs>3</NbOfTxs>");
        assertThat(xml.substring(xml.indexOf("FRST"), xml.indexOf("RCUR"))).contains("canic-2-1").doesNotContain("canic-1-1", "canic-3-1");
    }

    @Test void T_12_06_canicNuriaIsTransliteratedAndALongDescriptionIsCutAt140() {
        String longer = "Quota Abonat — Setembre 2026 · " + "Classe de l'Àlex amb la Núria i en Pol ".repeat(5);
        var remittance = writer.write(run(), "remittance-3", List.of(debit("m1", 1, 6000, longer)), LocalDate.of(2026, 9, 1));
        String xml = stored.get(remittance.fileKey());
        String information = between(xml, "<Ustrd>", "</Ustrd>");
        assertThat(information).hasSize(140).isEqualTo(("Quota Abonat Setembre 2026 - " + "Classe de l'Alex amb la Nuria i en Pol ".repeat(5)).substring(0, 140))
                .endsWith("Nuria i e").matches("[A-Za-z0-9/\\-?:().,'+ ]+");
        assertThat(SepaText.of("Cànic · Núria", 140)).isEqualTo("Canic - Nuria");
        assertThat(between(xml, "<Dbtr>", "</Dbtr>")).contains("<Nm>Nuria Puig Canic</Nm>");
    }

    @Test void T_12_06_aLongClubSlugKeepsTheMessageIdWithin35CharactersAndItsSequence() {
        when(club.slug()).thenReturn("club-d-agility-canic-del-valles-oriental-associacio");
        when(remittances.forPeriod("2026-09")).thenReturn(List.of(mock(Remittance.class), mock(Remittance.class)));
        var remittance = writer.write(run(), "remittance-4", List.of(debit("m1", 1, 6000, "Quota")), LocalDate.of(2026, 9, 1));
        assertThat(remittance.messageId()).hasSizeLessThanOrEqualTo(35).endsWith("-2026-09-3").startsWith("club-d-agility-canic");
        assertThat(stored.get(remittance.fileKey())).contains("<MsgId>" + remittance.messageId() + "</MsgId>");
        assertThat(SepaDirectDebits.paymentInformationId("club-d-agility-canic-del-valles-oriental", "2026-09", 12, SepaDirectDebits.SequenceType.RCUR))
                .hasSizeLessThanOrEqualTo(35).endsWith("-2026-09-12-RCUR");
    }

    @Test void T_12_06_aCollectionDateTooSoonIs422WithTheEarliestDayAndWritesNothing() {
        // Tuesday 25-08 at 10:00 in Madrid: two business days later is Thursday 27-08.
        assertThatThrownBy(() -> writer.write(run(), "remittance-5", List.of(debit("m1", 1, 6000, "Quota")), LocalDate.of(2026, 8, 26)))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.COLLECTION_DATE_TOO_SOON);
                    assertThat(failure.details()).isEqualTo(Map.of("requested", "2026-08-26", "earliest", "2026-08-27"));
                });
        assertThat(writer.write(run(), "remittance-6", List.of(debit("m1", 1, 6000, "Quota")), LocalDate.of(2026, 8, 27)).fileKey()).isNotNull();
        verify(files, times(1)).put(anyString(), any(Path.class), anyString());
    }

    @Test void T_12_06_aMemberWithoutAnIbanNeverReachesTheWriterAndTheWriterRefusesOne() {
        // E8-T02's rules skip a SEPA_DD member without an account as NO_BANK_ACCOUNT, so no collection is made for it…
        var member = new InvoicingRules.Member("m9", "ACTIVE", LocalDate.of(2026, 9, 1), PaymentMethodType.SEPA_DD, false, false, "plan");
        var settings = new InvoicingRules.Settings("EUR", 1, InvoicingRules.CashInvoicing.SEMESTER, 6, true, false, false, false, Set.of(PaymentMethodType.SEPA_DD));
        var month = InvoicingRules.month(List.of(member), List.of(), YearMonth.of(2026, 9), LocalDate.of(2026, 8, 25), settings, sources(), (origin, plan, at) -> "Quota");
        assertThat(month.invoices()).isEmpty();
        assertThat(month.skipped()).extracting(InvoicingRules.Incident::code).containsExactly(BillingIncidentCode.NO_BANK_ACCOUNT);
        // …and if one ever reached the writer (the account removed meanwhile), the run fails loudly naming the member.
        var debits = List.of(debit("m1", 1, 6000, "Quota"), debit("m2", 2, 6000, "Quota"));
        accounts.put("m2", new BillingCensusAccess.SepaAccount("m2", Map.of(), "Titular", "canic-2-1", Instant.parse("2026-07-15T10:00:00Z")));
        assertThatThrownBy(() -> writer.write(run(), "remittance-7", debits, LocalDate.of(2026, 9, 1)))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.SEPA_NOT_CONFIGURED);
                    assertThat(failure.details()).isEqualTo(Map.of("memberIds", List.of("m2")));
                });
        verifyNoInteractions(files);
    }

    @Test void R_12_12_aCollectionWithoutAMandateOrWithAnotherMandateThanTheMembersFailsLoudly() {
        var missing = debit("m1", 1, 6000, "Quota");
        var withoutMandate = new Collection(missing.id(), CLUB, missing.invoiceId(), CollectionProvider.SEPA_XML, missing.amount(), CollectionStatus.CREATED,
                null, "remittance-8", 1, null, null, List.of(), NOW, null, null, missing.endToEndId(), null, null);
        var changed = debit("m2", 2, 6000, "Quota");
        accounts.put("m2", new BillingCensusAccess.SepaAccount("m2", Map.of("iban", "ES0000000000000000000002"), "Titular", "canic-2-2", NOW));
        assertThatThrownBy(() -> writer.write(run(), "remittance-8", List.of(withoutMandate, changed), LocalDate.of(2026, 9, 1)))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.details()).isEqualTo(Map.of("memberIds", List.of("m1", "m2"))));
    }

    @Test void R_12_12_aMigratedMembersEncryptedIbanIsDecryptedOnlyIntoTheFileAndAnOldInvoiceTakesTheMandatesSignature() {
        var debit = debit("m1", 1, 6000, "Quota");
        String encrypted = vault.encrypt("ES0000000000000000000777", CLUB, "m1");
        accounts.put("m1", new BillingCensusAccess.SepaAccount("m1", Map.of("ibanEncrypted", encrypted), null, "canic-1-1", Instant.parse("2026-06-30T22:30:00Z")));
        // An invoice issued before E8-T03 has no frozen signature: the member's own (same mandate) is used, as a club-local date.
        var old = issued.get("invoice-m1");
        issued.put(old.id(), new Invoice(old.id(), CLUB, old.series(), old.number(), old.displayNumber(), old.issueDate(), old.period(), old.memberId(), old.memberSnapshot(),
                old.lines(), old.base(), old.tax(), old.total(), new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, "···· 0777", null, "canic-1-1", null, null),
                old.status(), old.kind(), old.runId(), old.remittanceId(), false, null, null, null, null, null, null, old.refundedTotal(), null, 0L, NOW, "admin", NOW, "admin"));
        var remittance = writer.write(run(), "remittance-9", List.of(debit), LocalDate.of(2026, 9, 1));
        assertThat(stored.get(remittance.fileKey())).contains("<IBAN>ES0000000000000000000777</IBAN>", "<DtOfSgntr>2026-07-01</DtOfSgntr>",
                "<Nm>Nuria Puig Canic</Nm>").doesNotContain(encrypted);
    }

    @Test void R_12_12_anIncompleteCreditorAnUnknownSchemaOrAnotherCurrencyAre422BeforeAnyFile() {
        var debits = List.of(debit("m1", 1, 6000, "Quota"));
        when(providers.sepaCreditor()).thenReturn(Optional.of(new BillingProviderSettings.SepaCreditor("Club", null, "ES0000000000000000009876", null, null)));
        assertCode(() -> writer.write(run(), "r", debits, LocalDate.of(2026, 9, 1)), ErrorCode.SEPA_NOT_CONFIGURED);
        when(providers.sepaCreditor()).thenReturn(Optional.empty());
        assertCode(() -> writer.write(run(), "r", debits, LocalDate.of(2026, 9, 1)), ErrorCode.SEPA_NOT_CONFIGURED);
        when(providers.sepaCreditor()).thenReturn(Optional.of(new BillingProviderSettings.SepaCreditor("Club", "ES00ZZZG00000000", "ES0000000000000000009876", "CAIXESBBXXX", null)));
        when(config.get("billing.sepa.schema", String.class)).thenReturn("pain.008.001.08");
        assertCode(() -> writer.write(run(), "r", debits, LocalDate.of(2026, 9, 1)), ErrorCode.SEPA_NOT_CONFIGURED);
        when(config.get("billing.sepa.schema", String.class)).thenReturn(null);
        when(club.currency()).thenReturn("USD");
        assertCode(() -> writer.write(run(), "r", debits, LocalDate.of(2026, 9, 1)), ErrorCode.CURRENCY_MISMATCH);
        when(club.currency()).thenReturn("EUR");
        var dollars = new Collection("c-usd", CLUB, debits.getFirst().invoiceId(), CollectionProvider.SEPA_XML, new Money(6000, "USD"), CollectionStatus.CREATED,
                null, "r", 1, null, null, List.of(), NOW, null, "canic-1-1", "2026-0001", null, null);
        assertCode(() -> writer.write(run(), "r", List.of(dollars), LocalDate.of(2026, 9, 1)), ErrorCode.CURRENCY_MISMATCH);
        verifyNoInteractions(files);
        assertThatThrownBy(() -> writer.write(run(), "r", List.of(), LocalDate.of(2026, 9, 1))).isInstanceOf(IllegalArgumentException.class);
        // A configured BIC is the creditor's agent; the creditor identifier keeps its own business code without a suffix.
        var remittance = writer.write(run(), "remittance-10", debits, LocalDate.of(2026, 9, 1));
        assertThat(stored.get(remittance.fileKey())).contains("<BIC>CAIXESBBXXX</BIC>", "<Id>ES00ZZZG00000000</Id>");
    }

    @Test void R_12_12_aDescriptionWithNothingInTheSepaSetHasNoRmtInfABlankBicIsNotProvidedAndAnAccountNeverPrintsItsIban() {
        when(providers.sepaCreditor()).thenReturn(Optional.of(new BillingProviderSettings.SepaCreditor("Club", "ES00ZZZG00000000", "ES0000000000000000009876", " ", null)));
        var remittance = writer.write(run(), "remittance-13", List.of(debit("m1", 1, 6000, "Дуна")), LocalDate.of(2026, 9, 1));
        assertThat(stored.get(remittance.fileKey())).doesNotContain("<RmtInf>", "<BIC>").contains("<Id>NOTPROVIDED</Id>");
        assertThat(accounts.get("m1").toString()).doesNotContain("ES00", "iban").contains("canic-1-1");
    }

    @Test void R_12_11_aFileTheSchemaRefusesIs422WithoutItsValueAndIsNeverStored() {
        // A creditor identifier the SEPA identifier pattern refuses: the XML is written but fails validation before the store.
        when(providers.sepaCreditor()).thenReturn(Optional.of(new BillingProviderSettings.SepaCreditor("Club", "NOT-AN-IDENTIFIER", "ES0000000000000000009876", null, null)));
        assertThatThrownBy(() -> writer.write(run(), "r", List.of(debit("m1", 1, 6000, "Quota")), LocalDate.of(2026, 9, 1)))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ErrorCode.SEPA_NOT_CONFIGURED);
                    assertThat(failure.details()).isEqualTo(Map.of("reason", "SCHEMA"));
                    assertThat(failure.getMessage()).doesNotContain("ES0000");
                });
        verifyNoInteractions(files);
    }

    @Test void R_12_11_theStoredFileIsDeletedWhenTheRunsTransactionRollsBackAndKeptWhenItCommits() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            var remittance = writer.write(run(), "remittance-11", List.of(debit("m1", 1, 6000, "Quota")), LocalDate.of(2026, 9, 1));
            var registered = TransactionSynchronizationManager.getSynchronizations();
            assertThat(registered).hasSize(1);
            registered.getFirst().afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verify(files, never()).delete(anyString());
            registered.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(files).delete(remittance.fileKey());
            doThrow(new IllegalStateException("store down")).when(files).delete(anyString());
            assertThatCode(() -> registered.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)).doesNotThrowAnyException();
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        // A storage failure aborts the run: the writer's caller (the run's transaction) sees it.
        doThrow(new java.io.UncheckedIOException(new java.io.IOException("store down"))).when(files).put(anyString(), any(Path.class), anyString());
        assertThatThrownBy(() -> writer.write(run(), "remittance-12", List.of(debit("m1", 1, 6000, "Quota")), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    // ---- fixtures

    static BillingRun run() {
        return new BillingRun("run-1", CLUB, "2026-09", BillingRunStatus.GENERATED, "simulation-1", List.of(), null, "2026-09-01", NOW, NOW, List.of(),
                List.of(), 1, "admin", null, null, null, NOW);
    }
    /** Member {@code id} (number {@code n}): a periodic invoice `2026-000n`, its SEPA collection and its clear `ES00` account. */
    Collection debit(String id, int n, long cents, String description) {
        var amount = new Money(cents, "EUR"); String number = String.format("2026-%04d", n);
        var line = new Invoice.Line(1, InvoiceLineOrigin.MONTHLY_FEE, null, null, description, amount, BigDecimal.ZERO, new Money(0, "EUR"), amount);
        issued.put("invoice-" + id, new Invoice("invoice-" + id, CLUB, "2026", n, number, "2026-08-25", "2026-09", id, new Invoice.MemberSnapshot(n, "Núria Puig Cànic", null),
                List.of(line), amount, new Money(0, "EUR"), amount, new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, "···· 000" + n, null,
                "canic-" + n + "-1", null, null, Instant.parse("2026-07-15T10:00:00Z")), InvoiceStatus.COLLECTING, InvoiceKind.PERIODIC, "run-1", "remittance",
                false, null, null, null, null, null, null, new Money(0, "EUR"), null, 0L, NOW, "admin", NOW, "admin"));
        accounts.put(id, new BillingCensusAccess.SepaAccount(id, Map.of("iban", String.format("ES00 0000 0000 0000 0000 %04d", n)), null, "canic-" + n + "-1",
                Instant.parse("2026-07-15T10:00:00Z")));
        return new Collection("collection-" + id, CLUB, "invoice-" + id, CollectionProvider.SEPA_XML, amount, CollectionStatus.CREATED, null, "remittance", 1, null,
                null, List.of(), NOW, null, "canic-" + n + "-1", number, null, null);
    }
    static Collection attempt(String id, String mandate, CollectionStatus status, String remittanceId) {
        return new Collection(id, CLUB, "invoice-" + id, CollectionProvider.SEPA_XML, new Money(6000, "EUR"), status, null, remittanceId, 1, null, null, List.of(),
                NOW, null, mandate, "2026-0" + id.length(), null, null);
    }
    static InvoicingRules.Sources sources() {
        return new InvoicingRules.Sources() {
            @Override public Optional<InvoicingRules.Plan> plan(String planId) { return Optional.of(new InvoicingRules.Plan(planId, InvoicingRules.PlanType.MONTHLY, false, "Abonat")); }
            @Override public Optional<InvoicingRules.Price> price(String planId, InvoiceLineOrigin concept, LocalDate day) {
                return Optional.of(new InvoicingRules.Price("price", new Money(6000, "EUR"), BigDecimal.ZERO));
            }
            @Override public Optional<Money> inactivityFee(String memberId, YearMonth month) { return Optional.empty(); }
            @Override public Optional<YearMonth> lastInvoicedMonth(String memberId) { return Optional.empty(); }
            @Override public List<InvoicingRules.Charge> charges(String memberId) { return List.of(); }
        };
    }
    static String between(String text, String from, String to) { int start = text.indexOf(from) + from.length(); return text.substring(start, text.indexOf(to, start)); }
    static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }
    /** A fictional 32-byte `BILLING_BANK_KEY` for the encrypted-IBAN case. */
    static final class BankAccountVaultKey {
        static final String KEY = Base64.getEncoder().encodeToString("e8-t03-fictional-key-32-bytes!!!".getBytes(StandardCharsets.US_ASCII));
    }
}

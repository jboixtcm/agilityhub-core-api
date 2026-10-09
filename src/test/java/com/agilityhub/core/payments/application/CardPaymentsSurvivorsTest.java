package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.application.ports.CardChargingPort;
import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.payments.domain.BillingRunStatus;
import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingRunRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingRun;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.PaymentOperation;
import com.agilityhub.core.payments.persistence.PaymentOperationRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link CardPayments} (S12 R-12-13/18/21/22; T-12-15, T-12-16): the provider guard, the replayed and
 * skipped card requests, which attempt and command a charge reuses, the run's card counters, the fenced writes after a provider
 * answer and the webhook settlement. Collaborators are mocks; fictional members only.
 */
class CardPaymentsSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");
    static final Instant AT = Instant.parse("2026-09-01T07:59:00Z");

    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final CollectionRepository collections = mock(CollectionRepository.class);
    final BillingRunRepository runs = mock(BillingRunRepository.class);
    final PaymentOperationRepository operations = mock(PaymentOperationRepository.class);
    final PaymentProviderRegistry provider = mock(PaymentProviderRegistry.class);
    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final BillingTransactions tx = mock(BillingTransactions.class);
    final BillingEvents events = mock(BillingEvents.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final PaymentRetryPolicy retries = mock(PaymentRetryPolicy.class);
    final PaymentAudits audit = mock(PaymentAudits.class);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    CardPayments service;

    @BeforeEach void setUp() {
        TenantContext.open(CLUB);
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(tx).run(any());
        service = new CardPayments(invoices, collections, runs, operations, provider, census, tx, events, configs, clock);
        ReflectionTestUtils.setField(service, "retries", retries);
        ReflectionTestUtils.setField(service, "audit", audit);
        when(invoices.transition(anyString(), anyLong(), any())).thenReturn(true);
        when(configs.get(CLUB)).thenReturn(config(3));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    // --- provider guard -----------------------------------------------------------------------------------------------------

    @Test void T_12_15_aRunIsNotChargedWithoutAnOffSessionProvider() {
        doThrow(new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED)).when(provider).require(PaymentProvider.Capability.OFF_SESSION);
        assertThatThrownBy(() -> service.chargeRun("run-1")).isInstanceOf(ApiException.class).hasMessage("PAYMENT_PROVIDER_NOT_ENABLED");
        verifyNoInteractions(tx, runs);
    }

    @Test void T_12_15_aRetryIsNotQueuedWithoutAnOffSessionProvider() {
        doThrow(new ApiException(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED)).when(provider).require(PaymentProvider.Capability.OFF_SESSION);
        assertThatThrownBy(() -> service.retry("invoice-1", 3L)).isInstanceOf(ApiException.class).hasMessage("PAYMENT_PROVIDER_NOT_ENABLED");
        verifyNoInteractions(tx, invoices);
    }

    // --- chargeRun ----------------------------------------------------------------------------------------------------------

    @Test void T_12_15_anUnknownRunIsNotFound() {
        assertThatThrownBy(() -> service.chargeRun("run-unknown")).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
    }

    @Test void T_12_15_aReplayedRequestAnswersItsStoredOperationsAndSkips() {
        when(runs.findById("run-1")).thenReturn(Optional.of(run(List.of("invoice-1", "invoice-2"))));
        when(runs.cardRequest("run-1", "request-1")).thenReturn(new Document("reference", "request-1").append("operations", List.of("op-1"))
                .append("skipped", List.of(new Document("invoiceId", "invoice-2").append("reason", "NO_PAYMENT_METHOD"))));
        when(operations.findById("op-1")).thenReturn(Optional.of(operation("op-1", "CHARGE", "col-1", "pi_done")));

        CardChargingPort.Result result;
        try (var scope = IdempotentOperation.open(() -> { }, (status, body) -> { }, "request-1")) {
            result = service.chargeRun("run-1");
        }

        assertThat(result.submitted()).isEqualTo(1);
        // The stored skip comes back as it was recorded.
        assertThat(result.skipped()).containsExactly(new CardChargingPort.Skip("invoice-2", "NO_PAYMENT_METHOD"));
        verify(runs, never()).charging(any());
    }

    @Test void T_12_15_anInvoiceWithoutAUsableCardIsSkippedUnderTheKeysLock() {
        var locks = new AtomicInteger();
        when(runs.findById("run-1")).thenReturn(Optional.of(run(List.of("invoice-1"))));
        when(runs.charging("run-1")).thenReturn(true);
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice("invoice-1", PaymentMethodType.CARD, InvoiceStatus.PENDING, "run-1")));
        when(collections.forInvoice("invoice-1")).thenReturn(List.of(attempt("col-1", 1, CollectionStatus.CREATED)));

        CardChargingPort.Result result;
        try (var scope = IdempotentOperation.open(locks::incrementAndGet, (status, body) -> { }, "request-1")) {
            result = service.chargeRun("run-1");
        }

        // The key's row is locked before the request is read or written.
        assertThat(locks).hasValue(1);
        // The unused attempt fails with the reason, and the skip is stored as a document for a replay.
        verify(collections).resolve("col-1", CollectionStatus.FAILED, "NO_PAYMENT_METHOD", NOW);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Document>> skipped = ArgumentCaptor.forClass(List.class);
        verify(runs).cardRequest(eq("run-1"), eq("request-1"), eq(List.of()), skipped.capture());
        assertThat(skipped.getValue()).containsExactly(new Document("invoiceId", "invoice-1").append("reason", "NO_PAYMENT_METHOD"));
        assertThat(result.skipped()).containsExactly(new CardChargingPort.Skip("invoice-1", "NO_PAYMENT_METHOD"));
    }

    @Test void T_12_15_aCollectingInvoiceReusesItsOpenChargeCommandOnly() {
        when(runs.findById("run-1")).thenReturn(Optional.of(run(List.of("invoice-1"))));
        when(runs.charging("run-1")).thenReturn(true);
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice("invoice-1", PaymentMethodType.CARD, InvoiceStatus.COLLECTING, "run-1")));
        // A completed charge comes before the open charge (a refund command needs a PAID invoice, PaymentRefunds:38, so none here).
        when(operations.forTarget("invoice-1")).thenReturn(List.of(operation("op-done", "CHARGE", "col-0", "pi_done"), operation("op-open", "CHARGE", "col-1", null)));
        when(operations.findById("op-open")).thenReturn(Optional.of(operation("op-open", "CHARGE", "col-1", "pi_open")));

        var result = service.chargeRun("run-1");

        assertThat(result.submitted()).isEqualTo(1);
        verify(runs).cardRequest(eq("run-1"), isNull(), eq(List.of("op-open")), eq(List.of()));
    }

    @Test void T_12_15_aPendingInvoiceIsChargedOnItsCreatedAttemptWhichBecomesSubmitted() {
        when(runs.findById("run-1")).thenReturn(Optional.of(run(List.of("invoice-1"))));
        when(runs.charging("run-1")).thenReturn(true);
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice("invoice-1", PaymentMethodType.CARD, InvoiceStatus.PENDING, "run-1")));
        // An earlier failed attempt is listed first; only the CREATED one is charged.
        when(collections.forInvoice("invoice-1")).thenReturn(List.of(attempt("col-old", 1, CollectionStatus.FAILED), attempt("col-new", 2, CollectionStatus.CREATED)));
        when(census.card("member-1")).thenReturn(Optional.of(new BillingCensusAccess.Card("cus_1", "pm_1", "4242", "visa", false)));
        when(operations.findById(anyString())).thenAnswer(call -> Optional.of(operation(call.getArgument(0), "CHARGE", "col-new", "pi_done")));

        service.chargeRun("run-1");

        var inserted = ArgumentCaptor.forClass(PaymentOperation.class);
        verify(operations).insert(inserted.capture());
        assertThat(inserted.getValue().providerRef()).isEqualTo("col-new");
        verify(collections).resolve("col-new", CollectionStatus.SUBMITTED, null, null);
        verify(collections, never()).resolve(eq("col-old"), any(), any(), any());
    }

    @Test void T_12_15_theRunCountsOnlyItsCardInvoicesAsChargedOrFailed() {
        when(runs.findById("run-1")).thenReturn(Optional.of(run(List.of())));
        when(runs.charging("run-1")).thenReturn(true);
        when(invoices.forRun("run-1")).thenReturn(List.of(invoice("invoice-1", PaymentMethodType.CARD, InvoiceStatus.PAID, "run-1"),
                invoice("invoice-2", PaymentMethodType.SEPA_DD, InvoiceStatus.PAID, "run-1"),
                invoice("invoice-3", PaymentMethodType.CARD, InvoiceStatus.FAILED, "run-1"),
                invoice("invoice-4", PaymentMethodType.MANUAL, InvoiceStatus.FAILED, "run-1")));

        service.chargeRun("run-1");

        verify(runs).progress("run-1", 1, 1, true, NOW);
    }

    // --- execute ------------------------------------------------------------------------------------------------------------

    @Test void T_12_15_anExhaustedChargeKeepsTheIntentTheProviderAnsweredAndRecountsTheRun() {
        realRetries();
        when(provider.createOffSessionPayment(any())).thenReturn(new PaymentProvider.OffSessionResult("pi_1", "processing", null));
        // The first write of the intent is lost; the exhausted path writes it again.
        doThrow(new IllegalStateException("write lost")).doNothing().when(collections).submitted("col-1", "pi_1");
        when(operations.failed("op-1", NOW, 3)).thenReturn(true);
        when(operations.submissionUncertain("op-1")).thenReturn(true);
        when(collections.findById("col-1")).thenReturn(Optional.of(attempt("col-1", 1, CollectionStatus.SUBMITTED)));
        var invoice = invoice("invoice-1", PaymentMethodType.CARD, InvoiceStatus.COLLECTING, "run-1");
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice));
        when(invoices.forRun("run-1")).thenReturn(List.of(invoice));

        assertThatThrownBy(() -> service.execute("op-1")).isInstanceOf(IllegalStateException.class);

        verify(collections, times(2)).submitted("col-1", "pi_1");
        verify(collections, never()).resolve(any(), any(), any(), any());
        verify(runs).progress(eq("run-1"), anyInt(), anyInt(), anyBoolean(), eq(NOW));
    }

    @Test void T_12_15_aLostClaimWritesNoIntent() {
        realRetries();
        when(provider.createOffSessionPayment(any())).thenReturn(new PaymentProvider.OffSessionResult("pi_1", "processing", null));
        when(operations.fence("op-1", "token-1")).thenReturn(false);

        assertThatThrownBy(() -> service.execute("op-1")).isInstanceOf(ApiException.class).hasMessage("STALE_VERSION");

        verify(collections, never()).submitted(any(), any());
        verify(operations, never()).completed(any(), any());
    }

    @Test void T_12_15_aClaimLostAfterTheIntentWriteNeverCompletesTheCommand() {
        realRetries();
        when(provider.createOffSessionPayment(any())).thenReturn(new PaymentProvider.OffSessionResult("pi_1", "processing", null));
        when(operations.fence("op-1", "token-1")).thenReturn(true, false);

        assertThatThrownBy(() -> service.execute("op-1")).isInstanceOf(ApiException.class).hasMessage("STALE_VERSION");

        verify(collections).submitted("col-1", "pi_1");
        verify(operations, never()).completed(any(), any());
    }

    // --- retry --------------------------------------------------------------------------------------------------------------

    @Test void T_12_15_retryingAnUnknownInvoiceIsNotFound() {
        assertThatThrownBy(() -> service.retry("invoice-unknown", 3L)).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
    }

    @Test void T_12_15_aRetryPastTheMaximumReportsTheAttemptsMade() {
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice("invoice-1", PaymentMethodType.CARD, InvoiceStatus.FAILED, null)));
        when(census.card("member-1")).thenReturn(Optional.of(new BillingCensusAccess.Card("cus_1", "pm_1", "4242", "visa", false)));
        when(collections.forInvoice("invoice-1")).thenReturn(List.of(attempt("col-1", 1, CollectionStatus.FAILED),
                attempt("col-2", 2, CollectionStatus.FAILED), attempt("col-3", 3, CollectionStatus.FAILED)));

        var thrown = catchThrowable(() -> service.retry("invoice-1", 3L));

        assertThat(thrown).isInstanceOfSatisfying(ApiException.class, failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.MAX_ATTEMPTS);
            assertThat(failure.details()).isEqualTo(Map.of("attempts", 3, "max", 3));
        });
    }

    @Test void T_12_15_aRetryLocksItsKeyBeforeReplayingTheStoredCommand() {
        var locks = new AtomicInteger();
        when(operations.forRequest("request-1")).thenReturn(Optional.of(operation("op-prior", "CHARGE", "col-2", null)));

        String operation;
        try (var scope = IdempotentOperation.open(locks::incrementAndGet, (status, body) -> { }, "request-1")) {
            operation = service.retry("invoice-1", 3L);
        }

        assertThat(operation).isEqualTo("op-prior");
        assertThat(locks).hasValue(1);
    }

    // --- settle -------------------------------------------------------------------------------------------------------------

    @Test void T_12_16_anExpiredCardFailureInvalidatesTheChargedCardAndReconcilesOnlyItsCommand() {
        // A retry (attempt 2) after the first attempt failed on the member's former card: each attempt has its own CHARGE command.
        settling(attempt("col-1", 2, CollectionStatus.SUBMITTED), InvoiceStatus.COLLECTING);
        when(collections.forInvoice("invoice-1")).thenReturn(List.of(attempt("col-0", 1, CollectionStatus.FAILED), attempt("col-1", 2, CollectionStatus.SUBMITTED)));
        when(operations.forTarget("invoice-1")).thenReturn(List.of(charge("op-other", "col-0", "cus_other", "pm_other"), charge("op-1", "col-1", "cus_1", "pm_1")));
        when(census.invalidateCards("cus_1", "pm_1")).thenReturn(List.of("member-1", "member-2"));

        assertThat(service.settle("pi_1", "col-1", false, "expired_card", AT)).isTrue();

        // The attempt found by its id keeps the provider's intent.
        verify(collections).submitted("col-1", "pi_1");
        verify(operations).reconciled("op-1", "pi_1");
        verify(operations, never()).reconciled(eq("op-other"), any());
        // R-12-22: the card of this attempt's command, and every member holding it, is invalidated.
        verify(census).invalidateCards("cus_1", "pm_1");
        verify(census, never()).invalidateCards(eq("cus_other"), any());
        verify(events).publish(BillingEvent.Kind.MemberCardInvalidated, "member-1", Map.of("memberId", "member-1", "reason", "NO_PAYMENT_METHOD"));
        verify(events).publish(BillingEvent.Kind.MemberCardInvalidated, "member-2", Map.of("memberId", "member-2", "reason", "NO_PAYMENT_METHOD"));
        verify(collections).resolve("col-1", CollectionStatus.FAILED, "expired_card", AT);
    }

    @Test void T_12_15_aSettlementOfACancelledInvoiceChangesNothing() {
        settling(attempt("col-1", 1, CollectionStatus.SUBMITTED), InvoiceStatus.CANCELLED);

        assertThat(service.settle("pi_1", "col-1", true, null, AT)).isFalse();
        verify(collections, never()).resolve(any(), any(), any(), any());
    }

    @Test void T_12_15_aRepeatedFailureOfAFailedAttemptChangesNothing() {
        settling(attempt("col-1", 1, CollectionStatus.FAILED), InvoiceStatus.FAILED);

        assertThat(service.settle("pi_1", "col-1", false, "card_declined", AT)).isFalse();
        verify(collections, never()).resolve(any(), any(), any(), any());
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    /** The real retry policy over the mocked command repository: claim "token-1", the fence passes unless a test says otherwise. */
    private void realRetries() {
        ReflectionTestUtils.setField(service, "retries", new PaymentRetryPolicy(configs, operations, tx, clock));
        when(operations.findById("op-1")).thenReturn(Optional.of(charge("op-1", "col-1", "cus_1", "pm_1")));
        when(operations.claim("op-1", true)).thenReturn(new PaymentOperationRepository.Claim("token-1", false));
        when(operations.fence("op-1", "token-1")).thenReturn(true);
    }

    private void settling(Collection collection, InvoiceStatus status) {
        when(collections.findById("col-1")).thenReturn(Optional.of(collection));
        when(collections.forInvoice("invoice-1")).thenReturn(List.of(collection));
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice("invoice-1", PaymentMethodType.CARD, status, null)));
    }

    static ClubConfig config(int maxAttempts) {
        var club = new ClubConfig.ClubView(CLUB, "club-a", "Example Club", List.of("ca"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        return new ClubConfig(club, Map.of("billing.stripeMaxAttempts", maxAttempts), Set.of(), null, Map.of());
    }

    static BillingRun run(List<String> invoiceIds) {
        return new BillingRun("run-1", CLUB, "2026-09", BillingRunStatus.GENERATED, "simulation-1", invoiceIds, null, "2026-09-01", NOW, null,
                List.of(), List.of(), 1L, null, null, null, 1L, NOW);
    }

    static Invoice invoice(String id, PaymentMethodType type, InvoiceStatus status, String runId) {
        var total = new Money(4500, "EUR");
        return new Invoice(id, CLUB, "2026", 1, "2026-0001", "2026-09-01", "2026-09", "member-1", new Invoice.MemberSnapshot(1, "Laura Serra", null),
                List.of(), total, new Money(0, "EUR"), total, new Invoice.PaymentMethodSnapshot(type, null, null, null, "4242", null),
                status, InvoiceKind.PERIODIC, runId, null, false, null, null, null, null, null, null, new Money(0, "EUR"), null, 3L, NOW, null, NOW, null);
    }

    static Collection attempt(String id, int number, CollectionStatus status) {
        return new Collection(id, CLUB, "invoice-1", CollectionProvider.STRIPE, new Money(4500, "EUR"), status, null, null, number, null, null,
                List.of(), NOW, null);
    }

    static PaymentOperation operation(String id, String kind, String collectionId, String resultId) {
        return new PaymentOperation(id, CLUB, kind, "invoice-1", collectionId, new Money(4500, "EUR"), id, null, null, null, NOW, resultId);
    }

    static PaymentOperation charge(String id, String collectionId, String customerId, String paymentMethodId) {
        var request = new PaymentProvider.OffSessionRequest(new Money(4500, "EUR"), customerId, paymentMethodId, "invoice-1",
                Map.of("clubId", CLUB, "invoiceId", "invoice-1", "collectionId", collectionId));
        return new PaymentOperation(id, CLUB, "CHARGE", "invoice-1", collectionId, new Money(4500, "EUR"), "invoice-1", null, null, request, NOW, null);
    }
}

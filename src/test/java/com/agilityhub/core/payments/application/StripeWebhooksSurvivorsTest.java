package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.payments.domain.StripeEventOutcome;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.payments.persistence.StripeEvent;
import com.agilityhub.core.payments.persistence.StripeInbox;
import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link StripeWebhooks} (S12 R-12-21/22; T-12-15, T-12-16): the durable receipt keeps the body's
 * hash and only the whitelisted fields (an expanded object by its id, the decline codes); the handler runs under the row's
 * lock and publishes its outcome; an expired-card decline that changed the ledger invalidates the card; a Checkout payment
 * intent is left to Checkout and an off-session charge goes to the card ledger; a detached method nobody uses is ignored; a failed processing warns when
 * its attempts run out and publishes `FAILED` once. PAY-02 (13 Nov 2026): the retries follow a constant backoff, an outage spends no attempt and
 * a redelivery of a FAILED event processes it again.
 */
class StripeWebhooksSurvivorsTest {
    static final String CLUB = "club-a", ID = "evt_fake_1";
    static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");
    static final long CREATED = 1790236800L;
    static final Instant AT = Instant.ofEpochSecond(CREATED);

    final StripeInbox inbox = mock(StripeInbox.class);
    final BillingTransactions tx = mock(BillingTransactions.class);
    final CardPayments cards = mock(CardPayments.class);
    final PaymentRefunds refunds = mock(PaymentRefunds.class);
    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final BillingEvents events = mock(BillingEvents.class);
    final PaymentRetryPolicy retries = mock(PaymentRetryPolicy.class);
    final PaymentProviderRegistry provider = mock(PaymentProviderRegistry.class);
    final ObjectMapper mapper = new ObjectMapper();
    final com.agilityhub.core.platform.application.ClubPaymentProviders clubSettings=mock(com.agilityhub.core.platform.application.ClubPaymentProviders.class);
    StripeWebhooks webhooks;

    @BeforeEach void wire() {
        TenantContext.open(CLUB);
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(tx).run(any());
        webhooks = new StripeWebhooks(inbox, tx, cards, refunds, mock(CheckoutService.class), mock(SignupCheckoutRepository.class),
                mock(UpfrontPaymentRepository.class), census, events, Clock.fixed(NOW, ZoneOffset.UTC), mapper, provider);
        ReflectionTestUtils.setField(webhooks, "retries", retries);
        ReflectionTestUtils.setField(webhooks,"clubSettings",clubSettings);
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void T_12_15_theReceiptKeepsTheBodysHashAndOnlyTheWhitelistedFields() throws Exception {
        byte[] body = ("{\"id\":\"" + ID + "\",\"type\":\"payment_intent.payment_failed\",\"created\":" + CREATED + ",\"data\":{\"object\":{"
                + "\"id\":\"pi_fake_1\",\"customer\":{\"id\":\"cus_fake_1\",\"email\":\"laura.serra@example.test\"},\"amount\":9000,"
                + "\"last_payment_error\":{\"code\":\"card_declined\",\"decline_code\":\"expired_card\",\"message\":\"Your card has expired.\"},"
                + "\"metadata\":{\"invoiceId\":\"invoice-1\"}}}}").getBytes(StandardCharsets.UTF_8);

        webhooks.receive(CLUB, body);

        var event = ArgumentCaptor.forClass(StripeEvent.class);
        var work = ArgumentCaptor.forClass(Document.class);
        verify(inbox).receive(event.capture(), work.capture());
        assertThat(event.getValue().payloadHash()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        var object = work.getValue().get("object", Document.class);
        // An expanded object is kept by its id (never its e-mail); an absent field is not stored at all.
        assertThat(object).containsEntry("customer", "cus_fake_1").containsEntry("amount", 9000L).doesNotContainKey("status");
        assertThat(object.get("last_payment_error")).isEqualTo(new Document("code", "card_declined").append("decline_code", "expired_card"));
        assertThat(object.toJson()).doesNotContain("example.test", "Your card");
    }

    @Test void T_12_16_anExpiredCardDeclineThatSettlesInvalidatesTheCardUnderTheRowsLock() {
        stored("payment_intent.payment_failed", null, failedIntent("expired_card"));
        when(cards.settle("pi_fake_1", "collection-1", false, "expired_card", AT)).thenReturn(true);
        when(census.invalidateCards("cus_fake_1", "pm_fake_1")).thenReturn(List.of("member-1"));

        webhooks.process(ID);

        var order = inOrder(inbox, cards, census);
        order.verify(inbox).lock(ID);
        order.verify(cards).settle("pi_fake_1", "collection-1", false, "expired_card", AT);
        order.verify(census).invalidateCards("cus_fake_1", "pm_fake_1");
        verify(events).publish(BillingEvent.Kind.MemberCardInvalidated, "member-1", Map.of("memberId", "member-1", "reason", "NO_PAYMENT_METHOD"));
        verify(inbox).outcome(ID, "PROCESSED", NOW);
        verify(events).publish(BillingEvent.Kind.StripeWebhookReceived, ID,
                Map.of("eventId", ID, "type", "payment_intent.payment_failed", "outcome", "PROCESSED"));
    }

    @Test void T_12_15_onlyAnExpiredCardDeclineThatChangedTheLedgerInvalidatesTheCard() {
        // Another decline reason.
        stored("payment_intent.payment_failed", null, failedIntent("insufficient_funds"));
        when(cards.settle(any(), any(), anyBoolean(), any(), any())).thenReturn(true);
        webhooks.process(ID);
        // An expired-card decline the ledger already had (a repeated delivery).
        stored("payment_intent.payment_failed", null, failedIntent("expired_card"));
        when(cards.settle(any(), any(), anyBoolean(), any(), any())).thenReturn(false);
        webhooks.process(ID);

        verify(census, never()).invalidateCards(any(), any());
    }

    /**
     * Real metadata only: a Checkout payment intent carries the session's `{clubId, memberId, upfrontPaymentIds}` plus
     * `operationId` (StripePaymentProvider:56-58, CheckoutService:170, PaymentCheckouts:57-58), never an `invoiceId`; an
     * off-session card charge carries `{clubId, invoiceId, collectionId}` (CardPayments:97-98), never an `operationId`.
     */
    @Test void T_12_15_aCheckoutPaymentIntentIsLeftToCheckoutAndAnOffSessionChargeIsSettledByTheCardLedger() {
        stored("payment_intent.succeeded", null, new Document("id", "pi_fake_2").append("metadata",
                new Document("clubId", CLUB).append("memberId", "member-1").append("operationId", "operation-1")));
        webhooks.process(ID);
        verify(cards, never()).settle(any(), any(), anyBoolean(), any(), any());
        verify(inbox).outcome(ID, "IGNORED", NOW);

        stored("payment_intent.succeeded", null, new Document("id", "pi_fake_3")
                .append("metadata", new Document("clubId", CLUB).append("invoiceId", "invoice-1").append("collectionId", "collection-2")));
        when(cards.settle("pi_fake_3", "collection-2", true, "card_declined", AT)).thenReturn(true);
        webhooks.process(ID);
        verify(inbox).outcome(ID, "PROCESSED", NOW);
    }

    @Test void T_12_16_aDetachedMethodNoMemberUsesIsIgnored() {
        stored("payment_method.detached", null, new Document("id", "pm_fake_9"));
        when(census.invalidateCards(null, "pm_fake_9")).thenReturn(List.of());

        webhooks.process(ID);

        verify(inbox).outcome(ID, "IGNORED", NOW);
        verify(events, never()).publish(eq(BillingEvent.Kind.MemberCardInvalidated), any(), any());
    }

    @Test void T_12_15_aFailedProcessingWarnsWhenItsAttemptsRunOutAndPublishesFailed() {
        when(inbox.findById(ID)).thenReturn(Optional.of(event("payment_intent.succeeded", null)));
        when(inbox.work(ID)).thenThrow(new IllegalStateException("Mongo unavailable"));
        when(inbox.failed(ID, NOW, StripeWebhooks.RETRY_BACKOFF)).thenReturn(true);

        webhooks.process(ID);

        verify(retries).warn("Stripe event", ID);
        verify(events).publish(BillingEvent.Kind.StripeWebhookReceived, ID,
                Map.of("eventId", ID, "type", "payment_intent.succeeded", "outcome", "FAILED"));
    }

    @Test void T_12_15_aFailedRetryWithAttemptsLeftNeitherWarnsNorPublishesFailedTwice() {
        when(inbox.findById(ID)).thenReturn(Optional.of(event("payment_intent.succeeded", StripeEventOutcome.FAILED)));
        when(inbox.work(ID)).thenThrow(new IllegalStateException("Mongo unavailable"));
        when(inbox.failed(ID, NOW, StripeWebhooks.RETRY_BACKOFF)).thenReturn(false);

        webhooks.process(ID);

        verify(inbox).failed(ID, NOW, StripeWebhooks.RETRY_BACKOFF);
        verify(retries, never()).warn(any(), any());
        verifyNoInteractions(events);
    }

    /**
     * PAY-02 (owner decision of 13 Nov 2026): the webhook retries are a constant growing backoff over about a day — ten attempts,
     * the waits never shrinking — and not the club's `billing.stripeMaxAttempts` (R-12-18's card retries), which is never read.
     */
    @Test void PAY_02_aFailedProcessingFollowsTheConstantBackoffOverAboutADayAndNeverTheClubsAttemptLimit() {
        assertThat(StripeWebhooks.RETRY_BACKOFF).hasSize(9);
        for (int i = 1; i < StripeWebhooks.RETRY_BACKOFF.size(); i++) {
            assertThat(StripeWebhooks.RETRY_BACKOFF.get(i)).isGreaterThanOrEqualTo(StripeWebhooks.RETRY_BACKOFF.get(i - 1));
        }
        assertThat(StripeWebhooks.RETRY_BACKOFF.stream().reduce(java.time.Duration.ZERO, java.time.Duration::plus))
                .isEqualTo(java.time.Duration.ofHours(23).plusMinutes(51));
        stored("checkout.session.completed", null, new Document("id", "cs_fake_1").append("payment_status", "paid"));
        when(provider.cardDetails(any())).thenThrow(new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.NO_PAYMENT_METHOD));

        webhooks.process(ID);

        verify(inbox).failed(ID, NOW, StripeWebhooks.RETRY_BACKOFF);
        verify(inbox, never()).postpone(any(), any());
        verify(retries, never()).maxAttempts();
    }

    /** PAY-02: an outage of the provider (here Stripe's 429) spends no attempt: the event is FAILED and waits E8-T11's deferral. */
    @Test void PAY_02_anOutageOfTheProviderSpendsNoAttempt() {
        assertThat(StripeWebhooks.OUTAGE_DEFERRAL).isEqualTo(java.time.Duration.ofMinutes(5));
        stored("checkout.session.completed", null, new Document("id", "cs_fake_1").append("payment_status", "paid"));
        when(provider.cardDetails(any())).thenThrow(new PaymentNotSubmitted(com.agilityhub.core.shared.domain.ErrorCode.RATE_LIMITED));

        webhooks.process(ID);

        verify(inbox).postpone(ID, NOW.plus(java.time.Duration.ofMinutes(5)));
        verify(inbox, never()).failed(any(), any(), any());
        verify(retries, never()).warn(any(), any());
        verify(events).publish(BillingEvent.Kind.StripeWebhookReceived, ID,
                Map.of("eventId", ID, "type", "checkout.session.completed", "outcome", "FAILED"));
    }

    /** PAY-02: a new delivery of a stored event is processed again only when the inbox re-queued it (it was FAILED). */
    @Test void PAY_02_aRedeliveryIsProcessedOnlyWhenTheInboxRequeuedAFailedEvent() {
        byte[] body = ("{\"id\":\"" + ID + "\",\"type\":\"payment_method.detached\",\"created\":" + CREATED
                + ",\"data\":{\"object\":{\"id\":\"pm_fake_9\"}}}").getBytes(StandardCharsets.UTF_8);
        doThrow(new org.springframework.dao.DuplicateKeyException("evt_fake_1")).when(inbox).receive(any(), any());
        stored("payment_method.detached", StripeEventOutcome.FAILED, new Document("id", "pm_fake_9"));
        when(census.invalidateCards(null, "pm_fake_9")).thenReturn(List.of());

        when(inbox.requeue(eq(ID), any())).thenReturn(false);
        webhooks.receive(CLUB, body);
        verify(inbox, never()).lock(any());

        when(inbox.requeue(eq(ID), any())).thenReturn(true);
        webhooks.receive(CLUB, body);
        var work = ArgumentCaptor.forClass(Document.class);
        verify(inbox, times(2)).requeue(eq(ID), work.capture());
        assertThat(work.getValue().get("object", Document.class)).containsEntry("id", "pm_fake_9");
        verify(inbox).lock(ID);
        verify(inbox).outcome(ID, "IGNORED", NOW);
    }

    @Test void T_12_15_anExpandedObjectIsReadByItsId() throws Exception {
        var node = mapper.readTree("{\"customer\":{\"id\":\"cus_fake_1\",\"email\":\"laura.serra@example.test\"},\"payment_method\":\"pm_fake_1\",\"amount\":9000}");
        assertThat(StripeWebhooks.text(node, "customer")).isEqualTo("cus_fake_1");
        assertThat(StripeWebhooks.text(node, "payment_method")).isEqualTo("pm_fake_1");
        assertThat(StripeWebhooks.text(node, "amount")).isNull();
        assertThat(StripeWebhooks.text(node, "missing")).isNull();
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static StripeEvent event(String type, StripeEventOutcome outcome) {
        return new StripeEvent(ID, CLUB, ID, type, NOW, null, outcome, "hash-fake");
    }

    void stored(String type, StripeEventOutcome outcome, Document object) {
        when(inbox.findById(ID)).thenReturn(Optional.of(event(type, outcome)));
        when(inbox.work(ID)).thenReturn(new Document("created", CREATED).append("object", object));
    }

    static Document failedIntent(String declineCode) {
        return new Document("id", "pi_fake_1").append("customer", "cus_fake_1").append("payment_method", "pm_fake_1")
                .append("metadata", new Document("clubId", CLUB).append("invoiceId", "invoice-1").append("collectionId", "collection-1"))
                .append("last_payment_error", new Document("code", "card_declined").append("decline_code", declineCode));
    }
    @Test void T_17_13_R_17_07_suspendedClubLeavesItsStripeInboxUntouched() {
        when(clubSettings.suspended(CLUB)).thenReturn(true);
        try(var scope=TenantContext.open(CLUB)) {webhooks.process(ID);}
        verifyNoInteractions(inbox,cards,refunds);
    }

}

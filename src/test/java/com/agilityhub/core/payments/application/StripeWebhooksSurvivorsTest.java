package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.BillingEvent;
import com.agilityhub.core.payments.domain.StripeEventOutcome;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.payments.persistence.StripeEvent;
import com.agilityhub.core.payments.persistence.StripeInbox;
import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.shared.application.BillingCensusAccess;
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
 * its attempts run out and publishes `FAILED` once.
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
    final ObjectMapper mapper = new ObjectMapper();
    StripeWebhooks webhooks;

    @BeforeEach void wire() {
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(tx).run(any());
        webhooks = new StripeWebhooks(inbox, tx, cards, refunds, mock(CheckoutService.class), mock(SignupCheckoutRepository.class),
                mock(UpfrontPaymentRepository.class), census, events, Clock.fixed(NOW, ZoneOffset.UTC), mapper, mock(PaymentProviderRegistry.class));
        ReflectionTestUtils.setField(webhooks, "retries", retries);
    }

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
        when(retries.maxAttempts()).thenReturn(3);
        when(inbox.findById(ID)).thenReturn(Optional.of(event("payment_intent.succeeded", null)));
        when(inbox.work(ID)).thenThrow(new IllegalStateException("Mongo unavailable"));
        when(inbox.failed(ID, NOW, 3)).thenReturn(true);

        webhooks.process(ID);

        verify(retries).warn("Stripe event", ID);
        verify(events).publish(BillingEvent.Kind.StripeWebhookReceived, ID,
                Map.of("eventId", ID, "type", "payment_intent.succeeded", "outcome", "FAILED"));
    }

    @Test void T_12_15_aFailedRetryWithAttemptsLeftNeitherWarnsNorPublishesFailedTwice() {
        when(retries.maxAttempts()).thenReturn(3);
        when(inbox.findById(ID)).thenReturn(Optional.of(event("payment_intent.succeeded", StripeEventOutcome.FAILED)));
        when(inbox.work(ID)).thenThrow(new IllegalStateException("Mongo unavailable"));
        when(inbox.failed(ID, NOW, 3)).thenReturn(false);

        webhooks.process(ID);

        verify(inbox).failed(ID, NOW, 3);
        verify(retries, never()).warn(any(), any());
        verifyNoInteractions(events);
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
}

package com.agilityhub.core.payments.application;

import com.agilityhub.core.identity.application.IdentityTransactions;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.payments.persistence.SignupCheckoutSession;
import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.BookingOwnerAccess;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.IdempotentOperation;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import static com.agilityhub.core.payments.application.CheckoutServiceSurvivorsTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PAY-04 (owner decision of 13 Nov 2026, S04 R-04-26): an applicant who backed out of Stripe Checkout pays again at once. Another
 * request for rows that wait for the member's own open signup checkout expires that checkout at the provider first, outside any
 * transaction, then on our side in the request's transaction, and opens a new one; a checkout the provider does not expire stays
 * open and the request answers 409 INVALID_STATE. Collaborators are mocks; fictional data.
 */
class CheckoutRetryAfterCancelTest {
    final SignupPaymentAccess members = mock(SignupPaymentAccess.class);
    final UpfrontPayments payments = mock(UpfrontPayments.class);
    final CensusClubSettings clubs = mock(CensusClubSettings.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final SignupCheckoutRepository sessions = mock(SignupCheckoutRepository.class);
    @SuppressWarnings("unchecked") final ObjectProvider<PaymentProvider> gateways = mock(ObjectProvider.class);
    final PaymentProvider gateway = mock(PaymentProvider.class);
    final IdentityTransactions transactions = mock(IdentityTransactions.class);
    final List<String> steps = new CopyOnWriteArrayList<>();
    final List<UpfrontPayments.Submission> scope = List.of(new UpfrontPayments.Submission("dog-1", "submission-1"));
    CheckoutService checkout;

    @BeforeEach void setUp() {
        TenantContext.open(CLUB);
        when(gateways.getIfAvailable()).thenReturn(gateway);
        when(clubs.providerEnabled("STRIPE")).thenReturn(true);
        when(clubs.appHost()).thenReturn("club-a.example.test");
        when(configs.get(CLUB)).thenReturn(config());
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(transactions).run(any());
        checkout = new CheckoutService(members, payments, clubs, configs, sessions, gateways, transactions, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(IcuMessageSource.class), mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(checkout, "billingCensus", mock(BillingCensusAccess.class));
        ReflectionTestUtils.setField(checkout, "upfront", mock(UpfrontPaymentRepository.class));
        ReflectionTestUtils.setField(checkout, "bookings", mock(BookingOwnerAccess.class));
        doAnswer(call -> { steps.add("write"); return call.<Supplier<?>>getArgument(0).get(); }).when(members).write(any());
        doAnswer(call -> { steps.add("expire:" + call.getArgument(0)); return null; }).when(gateway).expire(anyString());
        when(members.member("member-1")).thenReturn(Map.of("status", "PENDING", "paymentMethod", Map.of("type", "CARD")));
        when(members.submissions("member-1")).thenReturn(scope);
        when(members.locales("member-1", scope)).thenReturn(Map.of(scope.getFirst(), "ca"));
        when(payments.due("member-1", scope, "EUR")).thenReturn(new Money(10000, "EUR"));
        when(payments.linesToCharge(eq("member-1"), any())).thenReturn(List.of(line("DUE")));
        when(gateway.createCheckoutSession(any())).thenAnswer(call -> "https://checkout.example.test/" + call.<PaymentProvider.Request>getArgument(0).sessionId());
        when(sessions.answered(anyString())).thenReturn(true);
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void PAY_04_anotherKeyExpiresTheMembersOpenCheckoutAtTheProviderFirstAndOpensANewOne() {
        when(sessions.openSignup("member-1")).thenReturn(List.of(open("session-1", "request-1", NOW.plusSeconds(3600), "payment-1")));
        // The row waits for session-1 until the provider's expiry; the request's transaction then finds it payable again.
        when(payments.lines("member-1", scope)).thenReturn(List.of(line("CHECKOUT_PENDING")), List.of(line("DUE")));
        when(sessions.finish("session-1", "EXPIRED", null)).thenAnswer(call -> steps.add("finish:session-1"));

        var result = create("request-2");

        assertThat(result.checkoutSessionId()).isNotEqualTo("session-1");
        assertThat(result.checkoutUrl()).isEqualTo("https://checkout.example.test/" + result.checkoutSessionId());
        // The provider first, outside any transaction; then, in the request's transaction, our side and the new checkout.
        assertThat(steps).containsExactly("expire:session-1", "write", "finish:session-1", "write");
        var order = inOrder(sessions, payments, gateway);
        order.verify(sessions).finish("session-1", "EXPIRED", null);
        order.verify(payments).checkout("member-1", "session-1", false);
        order.verify(sessions).insert(argThat(s -> !s.id().equals("session-1") && s.upfrontPaymentIds().equals(List.of("payment-1"))));
        order.verify(payments).pending(eq("member-1"), eq(List.of("payment-1")), argThat(id -> !id.equals("session-1")));
        order.verify(gateway).createCheckoutSession(argThat(request -> !request.sessionId().equals("session-1")));
    }

    @Test void PAY_04_aCheckoutTheProviderDoesNotExpireStaysOpenAndTheRequestAnswers409() {
        when(sessions.openSignup("member-1")).thenReturn(List.of(open("session-1", "request-1", NOW.plusSeconds(3600), "payment-1")));
        when(payments.lines("member-1", scope)).thenReturn(List.of(line("CHECKOUT_PENDING")));
        // Stripe refuses to expire a session that was completed meanwhile; an outage is refused alike.
        doThrow(PaymentNotSubmitted.refused(com.agilityhub.core.shared.domain.ErrorCode.PROVIDER_CONFIG_INVALID)).when(gateway).expire("session-1");

        assertThatThrownBy(() -> create("request-2")).isInstanceOf(ApiException.class).hasMessage("INVALID_STATE");

        verify(members, never()).write(any());
        verify(sessions, never()).finish(any(), any(), any());
        verify(payments, never()).checkout(any(), any(), anyBoolean());
        verify(gateway, never()).createCheckoutSession(any());
    }

    @Test void PAY_04_theSameRequestRetriedAfterALostAnswerNeverExpiresItsOwnCheckout() {
        var own = open("session-1", "request-1", NOW.plusSeconds(3600), "payment-1");
        when(sessions.openSignup("member-1")).thenReturn(List.of(own));
        when(sessions.openFor("member-1", "request-1", NOW)).thenReturn(Optional.of(own));

        var result = create("request-1");

        assertThat(result.checkoutSessionId()).isEqualTo("session-1");
        verify(gateway, never()).expire(any());
        verify(sessions, never()).finish(any(), any(), any());
        verify(sessions, never()).insert(any());
    }

    @Test void PAY_04_aLapsedCheckoutAndOneThatHoldsNoRowOfThisRequestAreNotSentToTheProvider() {
        // session-1 is past its expiresAt (releaseLapsed gives its rows back); session-2 charges a row outside this signup's scope.
        when(sessions.openSignup("member-1")).thenReturn(List.of(open("session-1", "request-1", NOW, "payment-1"),
                open("session-2", "request-0", NOW.plusSeconds(3600), "payment-9")));
        when(payments.lines("member-1", scope)).thenReturn(List.of(line("DUE")));
        when(sessions.lapsedSignup("member-1", NOW)).thenReturn(List.of(open("session-1", "request-1", NOW, "payment-1")));
        when(sessions.finish("session-1", "EXPIRED", null)).thenReturn(true);

        var result = create("request-2");

        assertThat(result.checkoutSessionId()).isNotIn("session-1", "session-2");
        verify(gateway, never()).expire(any());
        verify(sessions, never()).finish(eq("session-2"), any(), any());
        verify(payments).checkout("member-1", "session-1", false);
    }

    @Test void PAY_04_aRowThatStillWaitsForACheckoutThisRequestDidNotCloseAnswers409() {
        when(sessions.openSignup("member-1")).thenReturn(List.of(open("session-1", "request-1", NOW.plusSeconds(3600), "payment-1")));
        // A concurrent request closed session-1 first and its own new checkout holds the row now.
        when(payments.lines("member-1", scope)).thenReturn(List.of(line("CHECKOUT_PENDING")));
        when(sessions.finish("session-1", "EXPIRED", null)).thenReturn(false);

        assertThatThrownBy(() -> create("request-2")).isInstanceOf(ApiException.class).hasMessage("INVALID_STATE");

        verify(gateway).expire("session-1");
        verify(payments, never()).checkout(any(), any(), anyBoolean());
        verify(sessions, never()).insert(any());
        verify(gateway, never()).createCheckoutSession(any());
    }

    CheckoutService.Result create(String reference) {
        try (var key = IdempotentOperation.open(() -> { }, (status, body) -> { }, reference)) {
            return checkout.create("member-1", "token", SUCCESS, CANCEL, answer -> new byte[0]);
        }
    }

    static UpfrontPayments.Line line(String status) {
        return new UpfrontPayments.Line("payment-1", "ENTRY_FEE", "dog-1", new Money(10000, "EUR"), new Money(0, "EUR"), status, "STRIPE", "submission-1");
    }

    static SignupCheckoutSession open(String id, String requestRef, Instant expiresAt, String paymentId) {
        var request = new PaymentProvider.Request(id, CLUB, "member-1", "payment",
                List.of(new PaymentProvider.Item(paymentId, "Entry fee", new Money(10000, "EUR"))), "laura.serra@example.test", "member-1",
                Map.of("clubId", CLUB, "memberId", "member-1"), "off_session", SUCCESS, CANCEL, expiresAt);
        return new SignupCheckoutSession(id, CLUB, "member-1", "PENDING", "payment", List.of(paymentId), expiresAt, null, null, null, requestRef, request);
    }
}

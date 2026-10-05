package com.agilityhub.core.payments.application.stripe;

import com.agilityhub.core.payments.application.*;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.stripe.StripeClient;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.*;
import com.stripe.param.checkout.SessionCreateParams;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StripePaymentProviderTest {
    final ClubPaymentProviders clubs = mock(ClubPaymentProviders.class);
    final ProviderSecretVault vault = mock(ProviderSecretVault.class);
    final SignupCheckoutRepository sessions = mock(SignupCheckoutRepository.class);
    final StripeClient client = mock(StripeClient.class, RETURNS_DEEP_STUBS);
    final StripePaymentProvider provider = spy(new StripePaymentProvider(clubs, vault, new StripeCalls(millis -> {}), sessions));
    void configure(String mode, String key) {
        when(clubs.stripe("club-a")).thenReturn(Optional.of(new StripeProviderSettings(true, null, "encrypted-example", null, mode, "acct_example")));
        when(vault.decrypt("encrypted-example", "club-a", "STRIPE", "secretKeyEnc")).thenReturn(key);
    }
    @Test void T_12_32_modeMismatchAndMissingKeysFailBeforeAnyNetworkCall() {
        try (var tenant = TenantContext.open("club-a")) {
            for (String mode : List.of("live", "other")) {
                configure(mode, "sk_test_example");
                assertThatThrownBy(() -> provider.forgetCustomer("cus_example")).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_CONFIG_INVALID));
            }
            configure("test", "sk_" + "live_example");
            assertThatThrownBy(() -> provider.forgetCustomer("cus_example")).isInstanceOf(ApiException.class);
            when(clubs.stripe("club-a")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> provider.forgetCustomer("cus_example")).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED));
            verifyNoInteractions(client);
        }
    }
    @Test void T_12_15_offSessionAndRefundUseExplicitDomainKeysAndPerClubClient() throws Exception {
        configure("test", "sk_test_example"); doReturn(client).when(provider).newClient("sk_test_example");
        var intent = new PaymentIntent(); intent.setId("pi_example"); intent.setStatus("succeeded");
        when(client.v1().paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenReturn(intent);
        var refund = new Refund(); refund.setId("re_example"); refund.setStatus("succeeded");
        when(client.v1().refunds().create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(refund);
        try (var tenant = TenantContext.open("club-a")) {
            var result = provider.createOffSessionPayment(new PaymentProvider.OffSessionRequest(new Money(1200, "EUR"), "cus_example", "pm_example", "invoice:2", Map.of("clubId", "club-a")));
            assertThat(result.paymentIntentId()).isEqualTo("pi_example");
            assertThat(provider.refund("pi_example", new Money(200, "EUR"), "refund-key", "Reason").id()).isEqualTo("re_example");
            provider.forgetCustomer("cus_example");
        }
        var params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class); var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(client.v1().paymentIntents()).create(params.capture(), options.capture());
        assertThat(params.getValue().getConfirm()).isTrue(); assertThat(params.getValue().getOffSession()).isEqualTo(true);
        assertThat(params.getValue().getAmount()).isEqualTo(1200); assertThat(params.getValue().getCurrency()).isEqualTo("eur");
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("invoice:2"); assertThat(options.getValue().getStripeAccount()).isNull();
        verify(vault, times(3)).decrypt("encrypted-example", "club-a", "STRIPE", "secretKeyEnc");
    }
    @Test void T_12_16_checkoutParametersAndSavedProviderReferenceAreStable() throws Exception {
        configure("test", "sk_test_example"); doReturn(client).when(provider).newClient("sk_test_example");
        var session = new Session(); session.setId("cs_example"); session.setUrl("https://checkout.stripe.com/example");
        when(client.v1().checkout().sessions().create(any(SessionCreateParams.class), any(RequestOptions.class))).thenReturn(session);
        try (var tenant = TenantContext.open("club-a")) {
            var request = new PaymentProvider.Request("operation", "club-a", "member", "payment",
                    List.of(new PaymentProvider.Item("payment", "Entry fee", new Money(1200, "EUR"))), null, "booking",
                    Map.of("upfrontPaymentIds", List.of("payment")), "off_session", "https://app.example.test/ok", "https://app.example.test/ko", Instant.parse("2026-10-05T00:00:00Z"));
            assertThat(provider.createCheckoutSession(request)).isEqualTo(session.getUrl());
        }
        var params = ArgumentCaptor.forClass(SessionCreateParams.class); var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(client.v1().checkout().sessions()).create(params.capture(), options.capture());
        assertThat(params.getValue().getMode()).isEqualTo(SessionCreateParams.Mode.PAYMENT);
        assertThat(params.getValue().getMetadata()).containsEntry("operationId", "operation").containsEntry("upfrontPaymentIds", "payment");
        assertThat(params.getValue().getClientReferenceId()).isEqualTo("booking");
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("operation");
        verify(sessions).providerSession("operation", "cs_example");
    }
    @Test void T_12_31_setupAndExpandedCardUseTheClubAccount() throws Exception {
        configure("test", "sk_test_example"); doReturn(client).when(provider).newClient("sk_test_example");
        var customer = new com.stripe.model.Customer(); customer.setId("cus_example");
        when(client.v1().customers().create(any(CustomerCreateParams.class), any(RequestOptions.class))).thenReturn(customer);
        var session = new Session(); session.setId("cs_setup"); session.setUrl("https://checkout.stripe.com/example");
        when(client.v1().checkout().sessions().create(any(SessionCreateParams.class), any(RequestOptions.class))).thenReturn(session);
        var pm = new com.stripe.model.PaymentMethod(); var card = new com.stripe.model.PaymentMethod.Card(); card.setLast4("4242"); card.setBrand("visa"); pm.setCard(card);
        when(client.v1().paymentMethods().retrieve(eq("pm_example"), any(RequestOptions.class))).thenReturn(pm);
        var intent = new PaymentIntent(); intent.setPaymentMethod("pm_example"); intent.setCustomer("cus_example"); intent.setSetupFutureUsage("off_session");
        when(client.v1().paymentIntents().retrieve(eq("pi_example"), any(RequestOptions.class))).thenReturn(intent);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var tenant = TenantContext.open("club-a")) {
            var request = new PaymentProvider.Request("setup-op", "club-a", "member", "setup", List.of(), "member@example.test", "member",
                    Map.of("memberId", "member", "currency", "GBP"), null, "https://app.example.test/ok", "https://app.example.test/ko", Instant.parse("2026-10-05T00:00:00Z"));
            provider.createCheckoutSession(request);
            var params = ArgumentCaptor.forClass(SessionCreateParams.class);
            verify(client.v1().checkout().sessions()).create(params.capture(), any(RequestOptions.class));
            assertThat(params.getValue().getCustomer()).isEqualTo("cus_example"); assertThat(params.getValue().getCustomerEmail()).isNull();
            assertThat(params.getValue().getCurrency()).isEqualTo("gbp");
            assertThat(params.getValue().getSetupIntentData().getMetadata()).containsEntry("operationId", "setup-op");
            assertThat(provider.cardDetails(mapper.valueToTree(Map.of("payment_intent", "pi_example"))).last4()).isEqualTo("4242");
            assertThat(provider.cardDetails(mapper.valueToTree(Map.of("payment_method", "pm_example", "customer", "cus_example"))).usable()).isTrue();
            intent.setSetupFutureUsage(null);
            assertThat(provider.cardDetails(mapper.valueToTree(Map.of("payment_intent", "pi_example")))).isNull();
            assertThat(provider.cardDetails(mapper.createObjectNode())).isNull();
            pm.setCard(null);
            assertThatThrownBy(() -> provider.cardDetails(mapper.valueToTree(Map.of("payment_method", "pm_example")))).isInstanceOf(ApiException.class);
            when(sessions.providerSession("absent")).thenReturn(Optional.empty()); provider.expire("absent");
            when(sessions.providerSession("setup-op")).thenReturn(Optional.of("cs_setup")); provider.expire("setup-op");
            verify(client.v1().checkout().sessions()).expire(eq("cs_setup"), any(RequestOptions.class));
            assertThatThrownBy(() -> provider.complete("setup-op")).isInstanceOf(ApiException.class);
            assertThat(provider.supports(PaymentProvider.Capability.REFUND)).isTrue();
            assertThat(provider.callTimeout()).isLessThanOrEqualTo(PaymentProvider.MAX_CALL_TIMEOUT);
        }
        assertThat(new StripePaymentProvider(clubs, vault, new StripeCalls(), sessions).newClient("sk_test_example")).isNotNull();
    }
    @Test void T_12_16_disabledProviderStillExpiresAnExistingCheckoutButCannotCreateOne() throws Exception {
        configure("test", "sk_test_example"); doReturn(client).when(provider).newClient("sk_test_example");
        when(clubs.stripe("club-a")).thenReturn(Optional.of(new StripeProviderSettings(false, null, "encrypted-example", null, "test", null)));
        when(sessions.providerSession("operation")).thenReturn(Optional.of("cs_existing"));
        try (var tenant = TenantContext.open("club-a")) {
            provider.expire("operation");
            assertThatThrownBy(() -> provider.createOffSessionPayment(new PaymentProvider.OffSessionRequest(
                    new Money(100, "EUR"), "cus_example", "pm_example", "invoice", Map.of())))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED));
        }
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(client.v1().checkout().sessions()).expire(eq("cs_existing"), options.capture());
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("expire:operation");
        verify(client.v1().paymentIntents(), never()).create(any(PaymentIntentCreateParams.class), any(RequestOptions.class));
    }
    @Test void T_12_30_aDeclinedIntentKeepsItsReferenceAndOtherErrorsMapSafely() throws Exception {
        configure("test", "sk_test_example"); doReturn(client).when(provider).newClient("sk_test_example");
        var exception = new com.stripe.exception.CardException("private", null, "card_declined", null, "expired_card", null, 402, null);
        var error = new com.stripe.model.StripeError(); var intent = new PaymentIntent(); intent.setId("pi_declined");
        error.setPaymentIntent(intent); error.setDeclineCode("expired_card"); exception.setStripeError(error);
        when(client.v1().paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenThrow(exception);
        var request = new PaymentProvider.OffSessionRequest(new Money(100, "EUR"), "cus_example", "pm_example", "invoice", Map.of());
        try (var tenant = TenantContext.open("club-a")) {
            assertThat(provider.createOffSessionPayment(request).failureCode()).isEqualTo("expired_card");
            error.setPaymentIntent(null);
            assertThatThrownBy(() -> provider.createOffSessionPayment(request)).isInstanceOf(ApiException.class);
            exception.setStripeError(null);
            assertThatThrownBy(() -> provider.createOffSessionPayment(request)).isInstanceOf(ApiException.class);
            var refund = new Refund(); refund.setId("re_example");
            when(client.v1().refunds().create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(refund);
            provider.refund("ch_example", new Money(100, "EUR"), "refund", "Reason");
            var params = ArgumentCaptor.forClass(RefundCreateParams.class);
            verify(client.v1().refunds()).create(params.capture(), any(RequestOptions.class)); assertThat(params.getValue().getCharge()).isEqualTo("ch_example");
            when(vault.decrypt(anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("private"));
            assertThatThrownBy(() -> provider.forgetCustomer("cus_example")).isInstanceOf(ApiException.class);
        }
    }
    @Test void T_12_32_disabledTenantAndAlreadyDeletedCustomerAreHandledWithoutLeakingProviderErrors() throws Exception {
        configure("test", "sk_test_example"); doReturn(client).when(provider).newClient("sk_test_example");
        try (var tenant = TenantContext.open("club-a")) {
            var request = new PaymentProvider.Request("op", "club-b", "member", "payment", List.of(), null, null, Map.of(), null, null, null, Instant.EPOCH);
            assertThatThrownBy(() -> provider.createCheckoutSession(request)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.TENANT_MISMATCH));
            when(client.v1().customers().delete(eq("cus_example"), any(RequestOptions.class)))
                    .thenThrow(new com.stripe.exception.InvalidRequestException("private", null, null, "resource_missing", 404, null));
            assertThatCode(() -> provider.forgetCustomer("cus_example")).doesNotThrowAnyException();
            when(client.v1().customers().delete(eq("cus_example"), any(RequestOptions.class)))
                    .thenThrow(new com.stripe.exception.InvalidRequestException("private", null, null, "invalid", 400, null));
            assertThatThrownBy(() -> provider.forgetCustomer("cus_example")).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_CONFIG_INVALID));
            when(clubs.stripe("club-a")).thenReturn(Optional.of(new StripeProviderSettings(false, null, "encrypted-example", null, "test", null)));
            assertThatThrownBy(() -> provider.forgetCustomer("cus_example")).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED));
        }
    }

}

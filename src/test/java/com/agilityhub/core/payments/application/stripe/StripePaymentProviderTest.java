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
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StripePaymentProviderTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"sk", "rk"})
    void hostedLiveOptInAllowsStoredAndNewKeysOnlyForItsTenant(String prefix) throws Exception {
        String key = prefix + "_live_example";
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withPropertyValues("core.hosted-demo.enabled=true", "core.payments.stripe-live-enabled-club-ids=club-a")
                .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
                .withBean(StripePaymentProvider.class, () -> provider).run(context -> {
                    assertThat(context).hasNotFailed();
                    configure("live", key); doReturn(client).when(provider).newClient(key);
                    var account = new com.stripe.model.Account(); account.setId("acct_example_live");
                    when(client.v1().accounts().retrieveCurrent(any(RequestOptions.class))).thenReturn(account);
                    try (var tenant = TenantContext.open("club-a")) {
                        assertThat(provider.ping().mode()).isEqualTo("live");
                        assertThat(provider.ping("live", key).mode()).isEqualTo("live");
                        provider.forgetCustomer("cus_example");
                        assertThatThrownBy(() -> provider.ping("live", prefix + "_test_example")).isInstanceOf(ApiException.class);
                    }
                    verify(client.v1().accounts(), times(2)).retrieveCurrent(any(RequestOptions.class));
                    verify(client.v1().customers()).delete(eq("cus_example"), any(RequestOptions.class));
                    clearInvocations(provider, vault);
                    when(clubs.stripe("club-b")).thenReturn(Optional.of(new StripeProviderSettings(true, null, "encrypted-example", null, "live", null)));
                    try (var tenant = TenantContext.open("club-b")) {
                        assertThatThrownBy(provider::ping).isInstanceOf(ApiException.class);
                        assertThatThrownBy(() -> provider.ping("live", key)).isInstanceOf(ApiException.class);
                    }
                    assertThatThrownBy(() -> provider.ping("live", key)).isInstanceOf(ApiException.class);
                    verify(provider, never()).newClient(anyString()); verifyNoInteractions(vault);
                });
    }

    @Test void hostedDemoRejectsLiveOperationsIncludingPingAndExpiredSessionsBeforeNetwork() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withPropertyValues("core.hosted-demo.enabled=true")
                .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
                .withBean(StripePaymentProvider.class, () -> provider).run(context -> {
                    assertThat(context).hasNotFailed(); configure("live", "sk_live_example");
                    when(sessions.providerSession("checkout")).thenReturn(Optional.of("cs_live_example"));
                    try (var tenant = TenantContext.open("club-a")) {
                        assertThatThrownBy(provider::ping).isInstanceOfSatisfying(ApiException.class,
                                e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_CONFIG_INVALID));
                        assertThatThrownBy(() -> provider.forgetCustomer("cus_example")).isInstanceOf(ApiException.class);
                        assertThatThrownBy(() -> provider.expire("checkout")).isInstanceOf(ApiException.class);
                        configure("test", "sk_live_example");
                        assertThatThrownBy(provider::ping).isInstanceOf(ApiException.class);
                    }
                    verifyNoInteractions(client);
                    verify(vault).decrypt("encrypted-example", "club-a", "STRIPE", "secretKeyEnc");
                    configure("test", "sk_test_example"); doReturn(client).when(provider).newClient("sk_test_example");
                    try (var tenant = TenantContext.open("club-a")) { provider.forgetCustomer("cus_example"); }
                    verify(client.v1().customers()).delete(eq("cus_example"), any(RequestOptions.class));
                });
    }
    /** E5-T31's review nit (done in E8-T06): the real adapter's per-request timeouts come from {@code callTimeout()}, with SDK retries off. */
    @Test void E5_T31_theRealAdapterBuildsItsClientBudgetFromCallTimeout() throws Exception {
        configure("test", "sk_test_example"); doReturn(client).when(provider).newClient("sk_test_example");
        doReturn(java.time.Duration.ofSeconds(15)).when(provider).callTimeout();
        var intent = new PaymentIntent(); intent.setId("pi_example"); intent.setStatus("succeeded");
        when(client.v1().paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenReturn(intent);
        try (var tenant = TenantContext.open("club-a")) {
            provider.createOffSessionPayment(new PaymentProvider.OffSessionRequest(new Money(100, "EUR"),
                    "cus_example", "pm_example", "timeout-contract", Map.of()));
        }
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(client.v1().paymentIntents()).create(any(PaymentIntentCreateParams.class), options.capture());
        assertThat(options.getValue().getConnectTimeout()).isEqualTo(500);
        assertThat(options.getValue().getReadTimeout()).isEqualTo(1500);
        assertThat(options.getValue().getMaxNetworkRetries()).isZero();
        assertThat(6L * (options.getValue().getConnectTimeout() + options.getValue().getReadTimeout()) + 600)
                .isLessThan(provider.callTimeout().toMillis());
    }
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
            assertThat(provider.refund("pi_example", new Money(200, "EUR"), "refund-key", "Reason", "operation-refund").id()).isEqualTo("re_example");
            provider.forgetCustomer("cus_example");
        }
        var params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class); var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(client.v1().paymentIntents()).create(params.capture(), options.capture());
        assertThat(params.getValue().getConfirm()).isTrue(); assertThat(params.getValue().getOffSession()).isEqualTo(true);
        assertThat(params.getValue().getAmount()).isEqualTo(1200); assertThat(params.getValue().getCurrency()).isEqualTo("eur");
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("invoice:2"); assertThat(options.getValue().getStripeAccount()).isNull();
        var refundParams = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(client.v1().refunds()).create(refundParams.capture(), any(RequestOptions.class));
        assertThat(refundParams.getValue().getMetadata()).isEqualTo(Map.of("reason", "Reason", "operationId", "operation-refund"));
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
        assertThat(params.getValue().toMap()).containsEntry("allowed_payment_method_types", List.of("card"))
                .doesNotContainKey("payment_method_types");
        assertThat(params.getValue().getMetadata()).containsEntry("operationId", "operation").containsEntry("upfrontPaymentIds", "payment");
        assertThat(params.getValue().getClientReferenceId()).isEqualTo("booking");
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("operation");
        verify(sessions).providerSession("operation", "cs_example");
    }
    /**
     * PAY-08 (owner decision of 13 Nov 2026, S04 R-04-26): a signup or S12 checkout ends at Stripe with S04's 24 hours, five minutes before
     * Core's own expiry (Stripe refuses more than 24 hours after it creates the session), and a retry of the stored request after a lost
     * answer sends the very same `expires_at`, whatever the time, so that Stripe's idempotency answers the same session.
     */
    @Test void PAY_08_aSignupCheckoutEndsWithCoresTwentyFourHoursAndARetrySendsTheSameExpiry() throws Exception {
        configure("test", "sk_test_example"); doReturn(client).when(provider).newClient("sk_test_example");
        var session = new Session(); session.setId("cs_example"); session.setUrl("https://checkout.stripe.com/example");
        when(client.v1().checkout().sessions().create(any(SessionCreateParams.class), any(RequestOptions.class))).thenReturn(session);
        var issued = Instant.parse("2026-10-04T10:00:00.250Z"); var coreExpiry = issued.plus(java.time.Duration.ofHours(24));
        var request = new PaymentProvider.Request("operation", "club-a", "member", "payment",
                List.of(new PaymentProvider.Item("payment", "Entry fee", new Money(1200, "EUR"))), "member@example.test", "member",
                Map.of("clubId", "club-a", "memberId", "member", "upfrontPaymentIds", List.of("payment")), "off_session",
                "https://app.example.test/ok", "https://app.example.test/ko", coreExpiry);
        try (var tenant = TenantContext.open("club-a")) {
            ReflectionTestUtils.setField(provider, "clock", java.time.Clock.fixed(issued, java.time.ZoneOffset.UTC));
            provider.createCheckoutSession(request);
            ReflectionTestUtils.setField(provider, "clock", java.time.Clock.fixed(issued.plus(java.time.Duration.ofHours(3)), java.time.ZoneOffset.UTC));
            provider.createCheckoutSession(request);
        }
        var params = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(client.v1().checkout().sessions(), times(2)).create(params.capture(), any(RequestOptions.class));
        long expected = Instant.parse("2026-10-05T09:55:00Z").getEpochSecond();
        assertThat(params.getAllValues()).extracting(SessionCreateParams::getExpiresAt).containsExactly(expected, expected);
        assertThat(params.getAllValues().getFirst().toMap()).containsEntry("expires_at", expected);
        assertThat(expected - issued.getEpochSecond()).isLessThan(24 * 3600).isGreaterThanOrEqualTo(30 * 60);
    }
    /**
     * PAY-08 (owner decision of 13 Nov 2026, S08 R-08-18): a PAY_TO_BOOK booking's checkout ends at the booking deadline, but never sooner
     * than Stripe's 30-minute minimum (plus the clock margin) nor later than its 24-hour maximum, counted from now.
     */
    @Test void PAY_08_aBookingCheckoutEndsAtTheBookingDeadlineWithinStripesWindow() throws Exception {
        configure("test", "sk_test_example"); doReturn(client).when(provider).newClient("sk_test_example");
        var session = new Session(); session.setId("cs_booking"); session.setUrl("https://checkout.stripe.com/example");
        when(client.v1().checkout().sessions().create(any(SessionCreateParams.class), any(RequestOptions.class))).thenReturn(session);
        var now = Instant.parse("2026-10-04T18:00:00Z"); var clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
        ReflectionTestUtils.setField(provider, "clock", clock);
        java.util.function.Function<Instant, PaymentProvider.Request> booking = deadline -> new PaymentProvider.Request("booking-op", "club-a", "member",
                "payment", List.of(new PaymentProvider.Item("payment", "Class", new Money(1200, "EUR"))), "member@example.test", "booking-1",
                Map.of("clubId", "club-a", "memberId", "member", "bookingId", "booking-1", "upfrontPaymentIds", List.of("payment")), null,
                "https://app.example.test/reserves/booking-1?payment=success", "https://app.example.test/reserves/booking-1?payment=cancel", deadline);
        try (var tenant = TenantContext.open("club-a")) { provider.createCheckoutSession(booking.apply(now.plus(java.time.Duration.ofMinutes(90)))); }
        var params = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(client.v1().checkout().sessions()).create(params.capture(), any(RequestOptions.class));
        assertThat(params.getValue().getExpiresAt()).isEqualTo(now.plus(java.time.Duration.ofMinutes(90)).getEpochSecond());
        // The default deadline (`bookings.paymentPendingMinutes` = 30, counted from the booking, before this call) and shorter ones.
        for (int minutes : List.of(0, 10, 29, 30, 35)) {
            assertThat(StripePaymentProvider.providerExpiry(booking.apply(now.plus(java.time.Duration.ofMinutes(minutes))), clock))
                    .isEqualTo(now.plus(java.time.Duration.ofMinutes(35)));
        }
        assertThat(StripePaymentProvider.providerExpiry(booking.apply(now.plus(java.time.Duration.ofMinutes(36))), clock))
                .isEqualTo(now.plus(java.time.Duration.ofMinutes(36)));
        assertThat(StripePaymentProvider.providerExpiry(booking.apply(now.plus(java.time.Duration.ofHours(48))), clock))
                .isEqualTo(now.plus(java.time.Duration.ofHours(24)).minus(java.time.Duration.ofMinutes(5)));
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
            // PAY-08: a card setup (signup or R-12-22) keeps S04's 24 hours too, five minutes before Core's own expiry.
            assertThat(params.getValue().getExpiresAt()).isEqualTo(Instant.parse("2026-10-04T23:55:00Z").getEpochSecond());
            assertThat(params.getValue().toMap()).containsEntry("allowed_payment_method_types", List.of("card"))
                    .doesNotContainKey("payment_method_types");
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

    @Test void T_17_05_R_17_05_pingUsesTheConfiguredAccountAndPropagatesRejection() throws Exception {
        configure("test","sk_test_example");doReturn(client).when(provider).newClient("sk_test_example");
        var account=new com.stripe.model.Account();account.setId("acct_example");
        var profile=new com.stripe.model.Account.BusinessProfile();profile.setName("Example Association");account.setBusinessProfile(profile);
        when(client.v1().accounts().retrieveCurrent(any(RequestOptions.class))).thenReturn(account);
        try(var scope=TenantContext.open("club-a")) {
            assertThat(provider.ping()).isEqualTo(new PaymentProvider.Connection(true,"acct_example","Example Association","test"));
            account.setBusinessProfile(null);assertThat(provider.ping().accountName()).isEqualTo("acct_example");
            when(client.v1().accounts().retrieveCurrent(any(RequestOptions.class))).thenThrow(new com.stripe.exception.AuthenticationException("Rejected",null,null,401));
            assertThatThrownBy(provider::ping).isInstanceOf(ApiException.class);
        }
    }

    /**
     * PAY-10 (owner decision of 13 Nov 2026, S17 R-17-05): the connection test of a key about to be saved uses that key, not the stored
     * configuration (none here), after the same mode and prefix checks; a key Stripe refuses throws.
     */
    @Test void PAY_10_aKeyAboutToBeSavedIsTestedWithThatKeyAlone() throws Exception {
        doReturn(client).when(provider).newClient("sk_live_fictional_new");
        var account = new com.stripe.model.Account(); account.setId("acct_example_new");
        when(client.v1().accounts().retrieveCurrent(any(RequestOptions.class))).thenReturn(account);
        assertThat(provider.ping("live", "sk_live_fictional_new")).isEqualTo(new PaymentProvider.Connection(true, "acct_example_new", "acct_example_new", "live"));
        verifyNoInteractions(clubs, vault);
        for (var refused : List.of(List.of("live", "sk_test_fictional_new"), List.of("other", "sk_other_fictional_new"))) {
            assertThatThrownBy(() -> provider.ping(refused.get(0), refused.get(1))).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_CONFIG_INVALID));
        }
        assertThatThrownBy(() -> provider.ping("test", null)).isInstanceOf(ApiException.class);
        ReflectionTestUtils.setField(provider, "hostedDemo", true);
        assertThatThrownBy(() -> provider.ping("live", "sk_live_fictional_new")).isInstanceOf(ApiException.class);
        ReflectionTestUtils.setField(provider, "hostedDemo", false);
        verify(provider, times(1)).newClient(anyString());
        when(client.v1().accounts().retrieveCurrent(any(RequestOptions.class))).thenThrow(new com.stripe.exception.AuthenticationException("Rejected", null, null, 401));
        assertThatThrownBy(() -> provider.ping("live", "sk_live_fictional_new")).isInstanceOf(ApiException.class);
    }

}

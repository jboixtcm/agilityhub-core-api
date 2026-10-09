package com.agilityhub.core.payments.application.stripe;

import com.agilityhub.core.payments.application.PaymentProvider;
import com.agilityhub.core.payments.application.ProviderSecretVault;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.platform.application.ClubPaymentProviders;
import com.agilityhub.core.platform.application.StripeProviderSettings;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import com.stripe.StripeClient;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** E11-T06 PIT survivors of {@link StripePaymentProvider} (S12 R-12-20/21/22; T-12-15, T-12-16, T-12-17). Fixture keys only. */
class StripePaymentProviderSurvivorsTest {
    final ClubPaymentProviders clubs = mock(ClubPaymentProviders.class);
    final ProviderSecretVault vault = mock(ProviderSecretVault.class);
    final SignupCheckoutRepository sessions = mock(SignupCheckoutRepository.class);
    final StripeClient client = mock(StripeClient.class, RETURNS_DEEP_STUBS);
    final StripePaymentProvider provider = spy(new StripePaymentProvider(clubs, vault, new StripeCalls(millis -> {}), sessions));

    @BeforeEach void configure() {
        when(clubs.stripe("club-a")).thenReturn(Optional.of(new StripeProviderSettings(true, null, "encrypted-example", null, "test", "acct_example")));
        when(vault.decrypt("encrypted-example", "club-a", "STRIPE", "secretKeyEnc")).thenReturn("sk_test_example");
        doReturn(client).when(provider).newClient("sk_test_example");
    }

    SessionCreateParams checkout(String email, String setupFutureUsage) throws Exception {
        var session = new Session(); session.setId("cs_example"); session.setUrl("https://checkout.stripe.com/example");
        when(client.v1().checkout().sessions().create(any(SessionCreateParams.class), any(RequestOptions.class))).thenReturn(session);
        try (var tenant = TenantContext.open("club-a")) {
            provider.createCheckoutSession(new PaymentProvider.Request("operation", "club-a", "member", "payment",
                    List.of(new PaymentProvider.Item("payment", "Entry fee", new Money(1200, "EUR"))), email, "booking", Map.of(), setupFutureUsage,
                    "https://app.example.test/ok", "https://app.example.test/ko", Instant.parse("2026-10-05T00:00:00Z")));
        }
        var params = ArgumentCaptor.forClass(SessionCreateParams.class);
        verify(client.v1().checkout().sessions()).create(params.capture(), any(RequestOptions.class));
        return params.getValue();
    }

    /** A payment checkout pre-fills the payer's e-mail when the request has one. */
    @Test void T_12_16_aPaymentCheckoutCarriesTheCustomerEmail() throws Exception {
        assertThat(checkout("member@example.test", null).getCustomerEmail()).isEqualTo("member@example.test");
    }

    /** R-12-22: `setup_future_usage` creates a customer and saves the card for off-session charges. */
    @Test void T_12_16_setupFutureUsageCreatesACustomerAndSavesTheCardOffSession() throws Exception {
        var params = checkout(null, "off_session");
        assertThat(params.getCustomerCreation()).isEqualTo(SessionCreateParams.CustomerCreation.ALWAYS);
        assertThat(params.getPaymentIntentData().getSetupFutureUsage()).isEqualTo(SessionCreateParams.PaymentIntentData.SetupFutureUsage.OFF_SESSION);
    }

    /** Without `setup_future_usage` no customer is created and no card is saved. */
    @Test void T_12_16_withoutSetupFutureUsageNoCustomerIsCreatedAndNoCardSaved() throws Exception {
        var params = checkout(null, null);
        assertThat(params.getCustomerCreation()).isNull();
        assertThat(params.getPaymentIntentData().getSetupFutureUsage()).isNull();
    }

    /** R-12-20: the refund without an operation id answers the provider's refund. */
    @Test void T_12_17_theRefundWithoutAnOperationReturnsTheProvidersRefund() throws Exception {
        var refund = new Refund(); refund.setId("re_example"); refund.setStatus("pending");
        when(client.v1().refunds().create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(refund);
        PaymentProvider.RefundResult result;
        try (var tenant = TenantContext.open("club-a")) {
            result = provider.refund("pi_example", new Money(200, "EUR"), "refund-key", "Reason");
        }
        assertThat(result).isEqualTo(new PaymentProvider.RefundResult("re_example", "pending"));
        var params = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(client.v1().refunds()).create(params.capture(), any(RequestOptions.class));
        assertThat(params.getValue().getMetadata()).isEqualTo(Map.of("reason", "Reason"));
    }

    /** R-12-21: an authentic delivery is parsed into its event; another secret's signature is WEBHOOK_SIGNATURE_INVALID. */
    @Test void T_12_15_aSignedWebhookIsParsedIntoItsEvent() throws Exception {
        var now = Instant.parse("2026-09-24T08:00:00Z");
        var webhooks = new StripePaymentProvider(clubs, vault, new StripeCalls(millis -> {}), sessions);
        ReflectionTestUtils.setField(webhooks, "clock", Clock.fixed(now, ZoneOffset.UTC));
        String secret = "whsec_fake_fake_fake";
        String payload = "{\"id\":\"evt_e11_fixture\",\"type\":\"payment_intent.succeeded\",\"created\":" + now.getEpochSecond()
                + ",\"data\":{\"object\":{\"id\":\"pi_example\"}}}";
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((now.getEpochSecond() + ".").getBytes(StandardCharsets.UTF_8));
        String header = "t=" + now.getEpochSecond() + ",v1=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));

        var event = webhooks.parseWebhook(payload, header, secret);
        assertThat(event).isNotNull();
        assertThat(event.id()).isEqualTo("evt_e11_fixture");
        assertThat(event.type()).isEqualTo("payment_intent.succeeded");
        assertThat(event.createdAt()).isEqualTo(now);
        assertThat(event.object().path("id").asText()).isEqualTo("pi_example");
        assertThatThrownBy(() -> webhooks.parseWebhook(payload, header, "whsec_fake_other_fake")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID));
    }
}

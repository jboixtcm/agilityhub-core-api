package com.agilityhub.core.payments.application;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link FakePaymentProvider}, the local/test provider the S12 ITs drive (T-12-15, T-12-17): forgetting
 * a customer twice calls it once, a refund without an operation answers and records the refund, and a signed webhook is parsed.
 */
class FakePaymentProviderSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");

    @SuppressWarnings("unchecked")
    final FakePaymentProvider fake = new FakePaymentProvider(mock(ObjectProvider.class));

    @BeforeEach void clock() { ReflectionTestUtils.setField(fake, "clock", Clock.fixed(NOW, ZoneOffset.UTC)); }

    @Test void T_12_15_forgettingACustomerTwiceCallsTheProviderOnce() {
        try (var tenant = TenantContext.open(CLUB)) {
            fake.forgetCustomer("cus_fake_1");
            fake.forgetCustomer("cus_fake_1");
        }
        assertThat(fake.calls()).extracting(FakePaymentProvider.Call::operation).containsExactly("forget");
    }

    @Test void T_12_17_aRefundWithoutOperationAnswersAndRecordsTheRefund() {
        PaymentProvider.RefundResult result;
        try (var tenant = TenantContext.open(CLUB)) {
            result = fake.refund("ch_fake_1", new Money(1500, "EUR"), "refund-key-1", "requested_by_customer");
        }
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.id()).startsWith("re_fake_");
        assertThat(fake.calls()).singleElement().satisfies(call -> {
            assertThat(call.operation()).isEqualTo("refund");
            assertThat(call.request()).isEqualTo(Map.of("chargeId", "ch_fake_1", "amount", new Money(1500, "EUR"), "reason", "requested_by_customer"));
        });
    }

    @Test void T_12_15_aSignedWebhookIsParsedIntoItsEvent() {
        String payload = "{\"id\":\"evt_fake_1\",\"type\":\"charge.refunded\",\"created\":" + NOW.getEpochSecond() + ",\"data\":{\"object\":{\"id\":\"ch_fake_1\"}}}";
        String secret = "fictional-webhook-secret";
        long timestamp = NOW.getEpochSecond();
        String signature = "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(StripeWebhookSignatures.hmac(secret, timestamp, payload.getBytes(StandardCharsets.UTF_8)));
        var event = fake.parseWebhook(payload, signature, secret);
        assertThat(event).isNotNull();
        assertThat(event.id()).isEqualTo("evt_fake_1");
        assertThat(event.type()).isEqualTo("charge.refunded");
        assertThat(event.object().path("id").asText()).isEqualTo("ch_fake_1");
    }
}

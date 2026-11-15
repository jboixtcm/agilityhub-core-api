package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.ClubPaymentProviders;
import com.agilityhub.core.platform.application.StripeProviderSettings;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PAY-10 (S12 R-12-21 «Mode test/live per club»): outside the hosted demo, an authentic Stripe event whose `livemode`
 * contradicts the club's configured mode answers `401 WEBHOOK_SIGNATURE_INVALID` with its SecurityEvent, as the hosted demo
 * already refuses a live event; an event of the configured mode, or one without a boolean `livemode`, is accepted as before.
 * Fixture secrets only.
 */
class StripeWebhookModeTest {
    static final String CLUB = "club-a";
    final ClubPaymentProviders providers = mock(ClubPaymentProviders.class);
    final ProviderSecretVault vault = mock(ProviderSecretVault.class);
    final SecurityEvents securityEvents = mock(SecurityEvents.class);
    final StripeWebhookSignatures signatures = new StripeWebhookSignatures(providers, vault, securityEvents,
            Clock.fixed(StripeWebhookSignaturesTest.NOW, ZoneOffset.UTC));

    @Test void PAY_10_aTestEventSignedForALiveClubIsRefused() throws Exception {
        configured("live");

        assertRefused("{\"id\":\"evt_fake_1\",\"livemode\":false}");
        assertAccepted("{\"id\":\"evt_fake_2\",\"livemode\":true}");
        verify(securityEvents).record(SecurityEvents.Type.WEBHOOK_SIGNATURE_INVALID, null, CLUB);
    }

    @Test void PAY_10_aLiveEventSignedForATestClubIsRefused() throws Exception {
        configured("test");

        assertRefused("{\"id\":\"evt_fake_1\",\"livemode\":true}");
        assertAccepted("{\"id\":\"evt_fake_2\",\"livemode\":false}");
        verify(securityEvents).record(SecurityEvents.Type.WEBHOOK_SIGNATURE_INVALID, null, CLUB);
    }

    @Test void PAY_10_anEventWithoutABooleanLivemodeKeepsTheSignatureCheckOnly() throws Exception {
        for (String mode : new String[] {"test", "live"}) {
            configured(mode);
            for (String body : new String[] {"{\"id\":\"evt_fake_1\"}", "{\"livemode\":\"true\"}", "{\"livemode\":null}", "[]", "not-json"}) {
                assertAccepted(body);
            }
        }
        verifyNoInteractions(securityEvents);
    }

    private void configured(String mode) {
        when(providers.stripe(CLUB)).thenReturn(Optional.of(new StripeProviderSettings(true, "pk_" + mode + "_fake", "secret", "encrypted", mode, null)));
        when(vault.decrypt("encrypted", CLUB, "STRIPE", "webhookSecretEnc")).thenReturn(StripeWebhookSignaturesTest.SECRET);
    }
    private void assertRefused(String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> signatures.authenticate(CLUB, signature(body), body)).as(json)
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID));
    }
    private void assertAccepted(String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        assertThatCode(() -> signatures.authenticate(CLUB, signature(body), body)).as(json).doesNotThrowAnyException();
    }
    private static String signature(byte[] body) throws Exception {
        long at = StripeWebhookSignaturesTest.NOW.getEpochSecond();
        return "t=" + at + ",v1=" + StripeWebhookSignaturesTest.v1(StripeWebhookSignaturesTest.SECRET, at, body);
    }
}

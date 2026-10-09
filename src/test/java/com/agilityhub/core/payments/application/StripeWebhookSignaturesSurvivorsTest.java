package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.ClubPaymentProviders;
import com.agilityhub.core.platform.application.StripeProviderSettings;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link StripeWebhookSignatures} (S12 R-12-21; T-12-15): a club whose webhook secret is not set
 * (absent or blank) answers `401 WEBHOOK_SIGNATURE_INVALID` with its SecurityEvent, even for a well-formed, fresh header,
 * and never reaches the vault or the HMAC.
 */
class StripeWebhookSignaturesSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");
    static final byte[] BODY = "{\"id\":\"evt_fake_1\",\"type\":\"payment_intent.succeeded\"}".getBytes(StandardCharsets.UTF_8);

    final ClubPaymentProviders providers = mock(ClubPaymentProviders.class);
    final ProviderSecretVault vault = mock(ProviderSecretVault.class);
    final SecurityEvents securityEvents = mock(SecurityEvents.class);
    final StripeWebhookSignatures signatures = new StripeWebhookSignatures(providers, vault, securityEvents, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void T_12_15_aClubWithoutAWebhookSecretRefusesEvenAWellFormedFreshSignature() {
        // Fresh `t` and a hex `v1`: only the missing secret can refuse it.
        String header = "t=" + NOW.getEpochSecond() + ",v1=" + "0".repeat(64);
        for (String stored : Arrays.asList(null, "", "   ")) {
            when(providers.stripe(CLUB)).thenReturn(Optional.of(new StripeProviderSettings(true, "pk_test_fake", null, stored, "test", null)));

            assertThatThrownBy(() -> signatures.authenticate(CLUB, header, BODY)).as(String.valueOf(stored))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.WEBHOOK_SIGNATURE_INVALID));
        }
        verify(securityEvents, times(3)).record(SecurityEvents.Type.WEBHOOK_SIGNATURE_INVALID, null, CLUB);
        verifyNoInteractions(vault);
    }
}

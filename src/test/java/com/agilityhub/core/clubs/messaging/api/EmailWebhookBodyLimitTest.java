package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.BrevoWebhookAuthentication;
import com.agilityhub.core.clubs.messaging.application.BrevoWebhookService;
import com.agilityhub.core.clubs.messaging.application.SendGridSignatureVerifier;
import com.agilityhub.core.clubs.messaging.application.SendGridWebhookService;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.StreamedBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CFG-03: the anonymous Brevo and SendGrid webhooks read at most 1 MiB + 1 byte of a body, so a longer one is never buffered
 * whole. Brevo keeps its order (the bearer secret first, then the cap); SendGrid refuses an oversized body before its signature.
 * An empty body answers `400 VALIDATION_ERROR` as Spring's required body did. Fixture secrets only.
 */
class EmailWebhookBodyLimitTest {
    static final long LARGE = 64L * 1024 * 1024;
    static final String TOKEN = "fixture-brevo-token";
    final SecurityEvents security = mock(SecurityEvents.class);
    final BrevoWebhookService brevoService = mock(BrevoWebhookService.class);
    final SendGridWebhookService sendGridService = mock(SendGridWebhookService.class);
    final BrevoWebhookController brevo = new BrevoWebhookController(new BrevoWebhookAuthentication(TOKEN), brevoService, new ObjectMapper(), security);
    final SendGridWebhookController sendGrid = new SendGridWebhookController(new SendGridSignatureVerifier(""), sendGridService, new ObjectMapper(), security);

    @Test void CFG_03_brevoRefusesAnOversizedAuthenticatedBodyWithoutReadingItWhole() {
        var request = new StreamedBody(LARGE);

        assertCode(() -> brevo.receive("Bearer " + TOKEN, request), ErrorCode.VALIDATION_ERROR);
        assertThat(request.bytesRead()).isEqualTo(BrevoWebhookController.MAX_BODY_BYTES + 1L);
        verifyNoInteractions(brevoService, security);
    }

    @Test void CFG_03_brevoStillAnswersAWrongSecretFirst() {
        var request = new StreamedBody(LARGE);

        assertCode(() -> brevo.receive("Bearer wrong", request), ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        assertThat(request.bytesRead()).isEqualTo(BrevoWebhookController.MAX_BODY_BYTES + 1L);
        verify(security).record(SecurityEvents.Type.WEBHOOK_SIGNATURE_INVALID, null, null);
        verifyNoInteractions(brevoService);
    }

    @Test void CFG_03_brevoAcceptsABodyAtTheCapAndRefusesAnEmptyOne() {
        brevo.receive("Bearer " + TOKEN, new StreamedBody(BrevoWebhookController.MAX_BODY_BYTES));
        verify(brevoService).accept(isNull(), isNull(), isNull(), isNull(), isNull());

        assertCode(() -> brevo.receive("Bearer " + TOKEN, new StreamedBody(0)), ErrorCode.VALIDATION_ERROR);
        verifyNoMoreInteractions(brevoService);
        verifyNoInteractions(security);
    }

    @Test void CFG_03_sendGridRefusesAnOversizedBodyBeforeItsSignature() {
        var request = new StreamedBody(LARGE);

        assertCode(() -> sendGrid.receive("1790000000", "c2lnbmF0dXJl", request), ErrorCode.VALIDATION_ERROR);
        assertThat(request.bytesRead()).isEqualTo(SendGridWebhookController.MAX_BODY_BYTES + 1L);
        assertCode(() -> sendGrid.receive("1790000000", "c2lnbmF0dXJl", new StreamedBody(0)), ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(sendGridService, security);
    }

    @Test void CFG_03_sendGridChecksTheSignatureOfABodyAtTheCap() {
        assertCode(() -> sendGrid.receive("1790000000", "c2lnbmF0dXJl", new StreamedBody(SendGridWebhookController.MAX_BODY_BYTES)),
                ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        verify(security).record(SecurityEvents.Type.WEBHOOK_SIGNATURE_INVALID, null, null);
        verifyNoInteractions(sendGridService);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code()).isEqualTo(code));
    }
}

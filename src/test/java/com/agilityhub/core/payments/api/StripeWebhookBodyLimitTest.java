package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.payments.application.StripeWebhookSignatures;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.support.StreamedBody;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CFG-03: the anonymous Stripe webhook reads at most 1 MiB + 1 byte of a body. A longer body answers `400 VALIDATION_ERROR`
 * before its signature is checked, without the rest being read; an empty body answers 400 as Spring's required body did; a body
 * at the cap still reaches the signature check.
 */
class StripeWebhookBodyLimitTest {
    final StripeWebhookSignatures signatures = mock(StripeWebhookSignatures.class);
    final BillingContractAccess access = mock(BillingContractAccess.class);
    final StripeWebhookController controller = new StripeWebhookController(access, signatures);

    @Test void CFG_03_anOversizedBodyIsRefusedWithoutBeingReadWhole() {
        var request = new StreamedBody(64L * 1024 * 1024);

        assertCode(() -> controller.receiveStripeEvent("club-a", "t=1,v1=00", request), ErrorCode.VALIDATION_ERROR);
        assertThat(request.bytesRead()).isEqualTo(StripeWebhookController.MAX_BODY_BYTES + 1L);
        verifyNoInteractions(signatures, access);
    }

    @Test void CFG_03_anEmptyBodyIsRefusedBeforeTheSignature() {
        assertCode(() -> controller.receiveStripeEvent("club-a", "t=1,v1=00", new StreamedBody(0)), ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(signatures, access);
    }

    @Test void CFG_03_aBodyAtTheCapReachesTheSignatureCheck() {
        doThrow(new ApiException(ErrorCode.WEBHOOK_SIGNATURE_INVALID)).when(signatures).authenticate(any(), any(), any());

        assertCode(() -> controller.receiveStripeEvent("club-a", "t=1,v1=00", new StreamedBody(StripeWebhookController.MAX_BODY_BYTES)),
                ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        verify(signatures).authenticate(eq("club-a"), eq("t=1,v1=00"), argThat(body -> body.length == StripeWebhookController.MAX_BODY_BYTES));
        verifyNoInteractions(access);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code()).isEqualTo(code));
    }
}

package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.payments.application.CheckoutService;
import com.agilityhub.core.payments.application.PaymentCheckouts;
import com.agilityhub.core.payments.application.PendingChargeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** E11-T06 PIT survivor of {@link MemberBillingController} (S12 §6, R-12-22; T-12-16): D10's card-setup link answers the provider's setup URL. */
class MemberBillingControllerSurvivorsTest {
    final BillingContractAccess access = mock(BillingContractAccess.class);
    final PaymentCheckouts checkouts = mock(PaymentCheckouts.class);

    @Test void T_12_16_theCardSetupLinkAnswersTheSetupSessionUrl() {
        var controller = new MemberBillingController(access, mock(PendingChargeService.class));
        ReflectionTestUtils.setField(controller, "checkouts", checkouts);
        ReflectionTestUtils.setField(controller, "mapper", mock(ObjectMapper.class));
        String success = "https://club-a.example.test/ok", cancel = "https://club-a.example.test/ko";
        when(checkouts.create(eq("member-1"), isNull(), eq(List.of()), eq(true), eq(success), eq(cancel), any()))
                .thenReturn(new CheckoutService.Result("https://checkout.example.test/setup/cs-1", "cs-1"));

        var link = controller.cardSetupLink("member-1", new BillingRequests.CardSetupRequest(success, cancel), UUID.randomUUID());

        assertThat(link).isEqualTo(new BillingContracts.CardSetupLink("https://checkout.example.test/setup/cs-1"));
        verify(access).mutableMember("member-1");
    }
}

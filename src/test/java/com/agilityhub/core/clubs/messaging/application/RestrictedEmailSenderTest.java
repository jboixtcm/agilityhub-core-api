package com.agilityhub.core.clubs.messaging.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestrictedEmailSenderTest {
    @Test void T_01_08_recoveryOptInCannotEnableOrdinaryEmail() {
        var provider = mock(EmailSender.class);
        var sender = new RestrictedEmailSender(provider, true, "");
        assertThat(sender.deliveryDisabled()).isTrue();
        assertThat(sender.send(null)).isEqualTo(EmailSender.SendResult.failed(DisabledEmailSender.DISABLED));
        assertThat(sender.forSignupApplicant("club").deliveryDisabled()).isTrue();
        verifyNoInteractions(provider);
        assertThat(sender.forPasswordRecovery()).isSameAs(provider);
        assertThat(new DisabledEmailSender().forPasswordRecovery().deliveryDisabled()).isTrue();
    }
    @Test void onlyTheConfiguredClubCanSendSignupReceiptsAndRejections() {
        var provider = mock(EmailSender.class);
        var sender = new RestrictedEmailSender(provider, false, "club");
        assertThat(sender.forSignupApplicant("club")).isSameAs(provider);
        assertThat(sender.forSignupApplicant("another").deliveryDisabled()).isTrue();
        assertThat(sender.forPasswordRecovery().deliveryDisabled()).isTrue();
    }
}

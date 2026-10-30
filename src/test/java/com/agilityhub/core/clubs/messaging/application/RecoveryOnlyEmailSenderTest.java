package com.agilityhub.core.clubs.messaging.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecoveryOnlyEmailSenderTest {
    @Test void T_01_08_recoveryOptInCannotEnableOrdinaryEmail() {
        var provider = mock(EmailSender.class);
        var sender = new RecoveryOnlyEmailSender(provider);
        assertThat(sender.deliveryDisabled()).isTrue();
        assertThat(sender.send(null)).isEqualTo(EmailSender.SendResult.failed(DisabledEmailSender.DISABLED));
        verifyNoInteractions(provider);
        assertThat(sender.forPasswordRecovery()).isSameAs(provider);
        assertThat(new DisabledEmailSender().forPasswordRecovery().deliveryDisabled()).isTrue();
    }
}

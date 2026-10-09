package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link FakeProviderCommand} (`bin/core checkout:fake-provider`, T-12-16 on a live local stack): the
 * command's name and the confirmation line the smoke reads.
 */
@ExtendWith(OutputCaptureExtension.class)
class FakeProviderCommandSurvivorsTest {
    final ClubConfigService clubs = mock(ClubConfigService.class);
    final FakeCheckoutGateway fake = mock(FakeCheckoutGateway.class);
    final FakeProviderCommand command = new FakeProviderCommand(clubs, fake);

    @Test void T_12_16_theCommandIsNamedAndReportsTheDeliveredCompletion(CapturedOutput output) {
        assertThat(command.name()).isEqualTo("checkout:fake-provider");
        when(clubs.findClubIdBySlug("fifo")).thenReturn(Optional.of("club-fifo"));
        command.run(new DefaultApplicationArguments("completion", "cs_fake_1", "--club=fifo"));
        verify(fake).completion("club-fifo", "cs_fake_1", null);
        assertThat(output.getOut()).contains("Fake provider completion delivered: checkoutSessionId=cs_fake_1");
    }
}

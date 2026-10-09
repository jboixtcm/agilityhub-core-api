package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** E11-T06 PIT survivor of {@link FakeWebhookCommand} (`bin/core billing:fake-webhook`, T-12-15): the confirmation line the smoke reads. */
@ExtendWith(OutputCaptureExtension.class)
class FakeWebhookCommandSurvivorsTest {
    final ClubConfigService clubs = mock(ClubConfigService.class);
    final FakeWebhookCommand command = new FakeWebhookCommand(clubs, mock(FakePaymentProvider.class),
            Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneOffset.UTC));

    @Test void T_12_15_theCommandReportsTheDeliveredWebhook(CapturedOutput output) {
        when(clubs.findClubIdBySlug("fifo")).thenReturn(Optional.of("club-fifo"));
        command.run(new DefaultApplicationArguments("payment_intent.succeeded", "pi_fake_1", "--club=fifo"));
        assertThat(output.getOut()).contains("Fake provider webhook delivered: payment_intent.succeeded");
    }
}

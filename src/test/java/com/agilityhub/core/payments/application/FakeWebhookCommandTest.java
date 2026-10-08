package com.agilityhub.core.payments.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** E8-T06 step 8: the smoke's card leg on the fake provider delivers `payment_intent.succeeded` to the club's webhook handler. */
class FakeWebhookCommandTest {
    final ClubConfigService clubs = mock(ClubConfigService.class);
    final FakePaymentProvider fake = mock(FakePaymentProvider.class);
    final FakeWebhookCommand command = new FakeWebhookCommand(clubs, fake, Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    @Test void T_12_15_theFakeWebhookReachesTheHandlerInTheClubsTenant() {
        when(clubs.findClubIdBySlug("fifo")).thenReturn(Optional.of("club-fifo"));
        doAnswer(call -> { assertThat(TenantContext.require()).isEqualTo("club-fifo"); return null; }).when(fake).deliverWebhook(anyString(), anyMap());
        command.run(new DefaultApplicationArguments("payment_intent.succeeded", "pi_fake_1", "--club=fifo"));
        verify(fake).deliverWebhook("payment_intent.succeeded", Map.of("eventId", "evt_fake_payment_intent_succeeded_pi_fake_1",
                "created", Instant.parse("2026-10-08T08:00:00Z").getEpochSecond(), "object", Map.of("id", "pi_fake_1")));
        assertThat(command.name()).isEqualTo("billing:fake-webhook");
    }

    @Test void T_12_15_wrongArgumentsAndUnknownClubsDeliverNothing() {
        for (String[] args : List.of(new String[]{"payment_intent.succeeded", "pi_1"}, new String[]{"charge.refunded", "pi_1", "--club=fifo"},
                new String[]{"payment_intent.succeeded", "in_1", "--club=fifo"}, new String[]{"payment_intent.succeeded", "pi_1", "--club=a", "--club=b"},
                new String[]{"payment_intent.succeeded", "pi_1", "--club=fifo", "--other=x"}, new String[]{"payment_intent.succeeded", "--club=fifo"})) {
            assertThatThrownBy(() -> command.run(new DefaultApplicationArguments(args))).isInstanceOf(IllegalArgumentException.class).hasMessage(FakeWebhookCommand.USAGE);
        }
        when(clubs.findClubIdBySlug("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> command.run(new DefaultApplicationArguments("payment_intent.succeeded", "pi_1", "--club=missing")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CLUB_NOT_FOUND));
        verifyNoInteractions(fake);
    }
}

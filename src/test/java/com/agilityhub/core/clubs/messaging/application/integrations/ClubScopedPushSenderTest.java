package com.agilityhub.core.clubs.messaging.application.integrations;

import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClubScopedPushSenderTest {
    private static final String CLUB = "12345678-3456-1234-8901-123456789012";
    private final PushSender delegate = mock(PushSender.class);
    private final PushSender sender = new ClubScopedPushSender(delegate, Set.of(CLUB));
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void T_11_07_onlyTheEnabledTenantPublishesTheKeyAndSendsItsSubscription() {
        var subscription = mock(PushSubscription.class);
        var payload = new PushPayload("n", "Title", "Body", null, "/notificacions", "N-24");
        when(subscription.clubId()).thenReturn(CLUB);
        when(delegate.publicKey()).thenReturn("public-key");
        when(delegate.send(subscription, payload)).thenReturn(PushResult.ok());
        assertThat(sender.publicKey()).isNull();
        assertThat(sender.send(subscription, payload).status()).isEqualTo(PushResult.Status.FAILED);
        try (var ignored = TenantContext.open("another-club")) {
            assertThat(sender.publicKey()).isNull();
            assertThat(sender.send(subscription, payload).status()).isEqualTo(PushResult.Status.FAILED);
        }
        verifyNoInteractions(delegate);
        try (var ignored = TenantContext.open(CLUB)) {
            assertThat(sender.publicKey()).isEqualTo("public-key");
            assertThat(sender.send(subscription, payload)).isEqualTo(PushResult.ok());
            assertThat(sender.send(null, payload).status()).isEqualTo(PushResult.Status.FAILED);
            when(subscription.clubId()).thenReturn("another-club");
            assertThat(sender.send(subscription, payload).status()).isEqualTo(PushResult.Status.FAILED);
        }
        verify(delegate, times(1)).send(subscription, payload);
    }

    @Test void T_11_07_scopeRequiresDistinctExactClubIds() {
        assertThat(ClubScopedPushSender.parseClubIds(null)).isEmpty();
        assertThat(ClubScopedPushSender.parseClubIds(" ")).isEmpty();
        assertThat(ClubScopedPushSender.parseClubIds(" " + CLUB + " ")).containsExactly(CLUB);
        for (String value : new String[] {"*", "all", CLUB + ",", CLUB + "," + CLUB, "not-a-club"}) {
            assertThatThrownBy(() -> ClubScopedPushSender.parseClubIds(value)).hasMessageContaining("PUSH_ENABLED_CLUB_IDS");
        }
    }
}

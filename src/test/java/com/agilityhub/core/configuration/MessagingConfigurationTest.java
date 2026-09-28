package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.application.engine.NotificationEngine;
import com.agilityhub.core.clubs.messaging.application.engine.NotificationEventHandler;
import com.agilityhub.core.clubs.messaging.application.integrations.AllowListSmsSender;
import com.agilityhub.core.clubs.messaging.application.integrations.FakePushSender;
import com.agilityhub.core.clubs.messaging.application.integrations.FakeSmsSender;
import com.agilityhub.core.clubs.messaging.application.integrations.LogSmsSender;
import com.agilityhub.core.clubs.messaging.application.integrations.TwilioSmsSender;
import com.agilityhub.core.clubs.messaging.application.integrations.WebPushSender;
import com.agilityhub.core.clubs.messaging.domain.NotificationEventEnvelope;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E7-T02 steps 2, 6 and 7: the providers are selected like the `EmailSender` (decision E13) — staging/prod refuse to start
 * without their credentials, `test` gets the doubles, `local` without credentials the log/in-memory senders, and outside
 * `prod` a real Twilio sender is wrapped by the `SMS_ALLOWED_NUMBERS` guard — and one outbox consumer per consumed event type
 * with the durable bean name `notifications.<EventType>`.
 */
class MessagingConfigurationTest {
    private final MessagingConfiguration configuration = new MessagingConfiguration();
    private final ObjectMapper mapper = new ObjectMapper();
    private static MockEnvironment profiles(String... active) { var environment = new MockEnvironment(); environment.setActiveProfiles(active); return environment; }

    @Test void T_11_06_theSmsSenderPerProfileAndTheNonProductionGuard() {
        assertThat(configuration.smsSender(profiles("test"), mapper, "", "", "", "")).isInstanceOf(FakeSmsSender.class);
        assertThat(configuration.smsSender(profiles("local"), mapper, "", "", "", "")).isInstanceOf(LogSmsSender.class);
        assertThatThrownBy(() -> configuration.smsSender(profiles("staging"), mapper, "", "", "", "")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TWILIO_ACCOUNT_SID");
        assertThatThrownBy(() -> configuration.smsSender(profiles("prod"), mapper, "AC-example", "", "", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> configuration.smsSender(profiles(), mapper, "", "", "", "")).isInstanceOf(IllegalStateException.class);
        assertThat(configuration.smsSender(profiles("prod"), mapper, "AC-example", "token-example", "", "+34600000001")).isInstanceOf(TwilioSmsSender.class);
        var staging = configuration.smsSender(profiles("staging"), mapper, "AC-example", "token-example", "MG-example", "+34600000001");
        assertThat(staging).isInstanceOf(AllowListSmsSender.class);
        assertThat(((AllowListSmsSender) staging).delegate()).isInstanceOf(TwilioSmsSender.class);
        assertThat(configuration.smsSender(profiles("local"), mapper, "AC-example", "token-example", "", "")).isInstanceOf(AllowListSmsSender.class);
    }

    @Test void T_11_10_thePushSenderPerProfileAndTheVapidKeys() throws Exception {
        var clubs = mock(ClubConfigService.class);
        assertThat(configuration.pushSender(profiles("test"), mapper, Clock.systemUTC(), clubs, "", "", "")).isInstanceOf(FakePushSender.class);
        assertThat(configuration.pushSender(profiles("local"), mapper, Clock.systemUTC(), clubs, "", "", "").publicKey()).isNull();
        assertThatThrownBy(() -> configuration.pushSender(profiles("prod"), mapper, Clock.systemUTC(), clubs, "", "", "")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VAPID_PUBLIC_KEY");
        assertThatThrownBy(() -> configuration.pushSender(profiles(), mapper, Clock.systemUTC(), clubs, "k", "", "")).isInstanceOf(IllegalStateException.class);
        var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); var keys = generator.generateKeyPair();
        var url = Base64.getUrlEncoder().withoutPadding();
        byte[] point = new byte[65]; point[0] = 4; copy(((ECPublicKey) keys.getPublic()).getW().getAffineX(), point, 1); copy(((ECPublicKey) keys.getPublic()).getW().getAffineY(), point, 33);
        byte[] scalar = new byte[32]; copy(((ECPrivateKey) keys.getPrivate()).getS(), scalar, 0);
        String publicKey = url.encodeToString(point), privateKey = url.encodeToString(scalar);
        var test = configuration.pushSender(profiles("test"), mapper, Clock.systemUTC(), clubs, publicKey, privateKey, "mailto:product@example.test");
        assertThat(test).isInstanceOf(FakePushSender.class); assertThat(test.publicKey()).isEqualTo(publicKey);
        var real = configuration.pushSender(profiles("staging"), mapper, Clock.systemUTC(), clubs, publicKey, privateKey, "mailto:product@example.test");
        assertThat(real).isInstanceOf(WebPushSender.class); assertThat(real.publicKey()).isEqualTo(publicKey);
    }
    private static void copy(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray(); int start = Math.max(0, bytes.length - 32), length = Math.min(32, bytes.length);
        System.arraycopy(bytes, start, target, offset + 32 - length, length);
    }

    @Test void T_11_23_theUnsubscribeKeyIsRequiredOutsideLocalAndTest() {
        var clock = Clock.systemUTC();
        assertThat(configuration.unsubscribeTokens(profiles("local"), clock, "")).isNotNull();
        assertThatThrownBy(() -> configuration.unsubscribeTokens(profiles("prod"), clock, " ")).isInstanceOf(IllegalStateException.class).hasMessageContaining("EMAIL_UNSUBSCRIBE_KEY");
        assertThat(configuration.unsubscribeTokens(profiles("staging"), clock, Base64.getEncoder().encodeToString(new byte[32])).issue("club-a", "member-a")).isNotBlank();
        assertThatThrownBy(() -> configuration.unsubscribeTokens(profiles("staging"), clock, "not base64!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> configuration.unsubscribeTokens(profiles("staging"), clock, Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalStateException.class);
    }

    @Test void WP_11_B_oneDurableOutboxConsumerPerConsumedEventType() {
        var factory = new DefaultListableBeanFactory();
        MessagingConfiguration.notificationEventHandlers().postProcessBeanDefinitionRegistry(factory);
        var names = java.util.Arrays.asList(factory.getBeanDefinitionNames());
        assertThat(names).allMatch(name -> name.startsWith(NotificationEventHandler.PREFIX)).hasSize(NotificationEventHandler.eventTypes().size())
                .contains("notifications.ClassCancelledByClub", "notifications.ReminderDue", "notifications.NoShowNoticeDue", "notifications.EmailBounced",
                        "notifications.SmsCapReached", "notifications.AnnouncementSent", "notifications.WaitlistNotified")
                // SYSTEM codes have their own path; LATER codes are never emitted at R1.
                .doesNotContain("notifications.MagicLinkRequested", "notifications.PasswordChanged", "notifications.ChallengePublished", "notifications.MembershipChanged");
        // Each handler hands its event to the engine, looked up lazily.
        @SuppressWarnings("unchecked") ObjectProvider<NotificationEngine> provider = mock(ObjectProvider.class);
        var engine = mock(NotificationEngine.class); when(provider.getObject()).thenReturn(engine);
        var handler = new NotificationEventHandler("ReminderDue", provider);
        var envelope = new NotificationEventEnvelope(null, "ReminderDue", "club-a", "Booking", "booking-a", java.time.Instant.EPOCH, Map.of(), null, null, null);
        handler.handle("event-a", envelope);
        verify(engine).handle("event-a", envelope);
        assertThat(handler.eventType()).isEqualTo("ReminderDue"); assertThat(handler.eventClass()).isEqualTo(NotificationEventEnvelope.class);
        assertThat(envelope.type()).isEqualTo("ReminderDue"); assertThat(new NotificationEventEnvelope("Kind", null, null, null, null, null, null, null, null, null).type()).isEqualTo("Kind");
        assertThatThrownBy(() -> new NotificationEventHandler(null, provider)).isInstanceOf(NullPointerException.class);
    }
}

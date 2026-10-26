package com.agilityhub.core.identity.api;

import com.agilityhub.core.clubs.messaging.application.EmailSender;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class DisabledEmailIdentityIT extends IdentityIntegrationSupport {
    @MockitoBean EmailSender disabledSender;
    @Test void internalPasswordLoginWorksWhileMagicAndResetEmailNeverReachSenderOrLogs(CapturedOutput output) throws Exception {
        when(disabledSender.deliveryDisabled()).thenReturn(true);
        assertThat(login().path("access_token").asText()).isNotBlank();
        for (String purpose : new String[]{"LOGIN", "RESET"}) {
            mvc.perform(post("/api/v1/auth/magic-link").header("Host", HOST).contentType("application/json")
                    .content(mapper.writeValueAsString(Map.of("email", "admin@example.test", "purpose", purpose, "client_id", "clubs-app"))))
                    .andExpect(status().isAccepted()).andExpect(content().string(""));
            awaitMail();
        }
        var rows = mongo.findAll(Notification.class);
        assertThat(rows).hasSize(2).allSatisfy(n -> {
            assertThat(n.status()).isEqualTo(Notification.Status.SKIPPED_NOT_ALLOWED);
            assertThat(n.sentAt()).isNull(); assertThat(n.providerMessageId()).isNull();
        });
        verify(disabledSender, never()).send(any());
        assertThat(output.getAll()).doesNotContain("admin@example.test", PASSWORD, "/activacio?t=", "purpose=reset");
    }
}

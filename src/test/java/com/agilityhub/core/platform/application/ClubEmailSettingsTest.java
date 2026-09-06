package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.domain.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClubEmailSettingsTest {
    @Test void T_11_25_senderRequiresTheExactVerifiedDomain() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var original = mapper.readTree(getClass().getResourceAsStream("/fixtures/platform/club.json"));
        var clubs = mock(ClubRepository.class); var configs = mock(ClubConfigService.class);
        var config = mock(ClubConfig.class); when(configs.get("club-a")).thenReturn(config);
        when(config.get("messaging.email.fromName", String.class)).thenReturn("Example Sender");
        when(config.get("auth.magicLinkMinutes", Integer.class)).thenReturn(15);
        var service = new ClubEmailSettings(clubs, configs);
        for (String candidate : java.util.Arrays.asList("sender@app.example.test", "sender@pending.example.test", "sender@sub.app.example.test", "sender@platform.example.test", "malformed", null)) {
            var club = mapper.treeToValue(original, Club.class); when(clubs.findById("club-a")).thenReturn(Optional.of(club));
            when(config.get("messaging.email.fromAddress", String.class)).thenReturn(candidate);
            when(config.get("messaging.email.replyTo", String.class)).thenReturn("");
            var settings = service.get("club-a", "platform@platform.example.test");
            assertThat(settings.fromAddress()).isEqualTo("sender@app.example.test".equals(candidate) || "sender@platform.example.test".equals(candidate)
                    ? candidate : "platform@platform.example.test");
            assertThat(settings.fromName()).isEqualTo("Example Sender"); assertThat(settings.replyTo()).isEqualTo("club@example.test");
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) original.at("/domains/0")).putNull("verifiedAt");
        when(clubs.findById("club-a")).thenReturn(Optional.of(mapper.treeToValue(original, Club.class)));
        when(config.get("messaging.email.fromAddress", String.class)).thenReturn("sender@app.example.test");
        when(config.get("messaging.email.replyTo", String.class)).thenReturn("reply@example.test");
        assertThat(service.get("club-a", "platform@platform.example.test").fromAddress()).isEqualTo("platform@platform.example.test");
        assertThat(service.get("club-a", "platform@platform.example.test").replyTo()).isEqualTo("reply@example.test");
        when(clubs.findById("club-a")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("club-a", "platform@platform.example.test")).isInstanceOf(ApiException.class);
    }
}

package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ProductBranding;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S14 §8 N-52 (E11-T01 round 2): the platform's name from configuration and the date in the language of the e-mail. */
class ErasureNoticesTest {
    static final String EXECUTE_AT = "2026-09-29T22:00:00Z";
    final SystemNotificationService notifications = mock(SystemNotificationService.class);
    final NotificationAccounts accounts = mock(NotificationAccounts.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final ProductBranding product = mock(ProductBranding.class);
    ErasureNotices notices;

    static ErasureForeignEvent event(String clubId, String source) {
        return new ErasureForeignEvent("AccountErasureRequested", clubId, "r", Instant.parse("2026-09-05T08:00:00Z"),
                Map.of("accountId", "montse-account", "executeAt", EXECUTE_AT, "source", source));
    }
    void account(String locale) { when(accounts.find("montse-account")).thenReturn(Optional.of(new NotificationAccounts.Recipient("montse-account", "montse@example.test", locale, null))); }
    @SuppressWarnings("unchecked")
    Map<String, Object> sent(String locale) {
        var variables = ArgumentCaptor.forClass(Map.class);
        verify(notifications).sendOnceLocalized(anyString(), eq("N-52"), eq("montse-account"), eq(locale), variables.capture());
        return variables.getValue();
    }
    @BeforeEach void club() throws Exception {
        notices = new ErasureNotices(notifications, accounts, configs, product, new IcuMessageSource());
        // The product's own branding, as `platform/product-branding.json` ships it (name, languages, zone).
        try (var input = ProductBranding.class.getResourceAsStream("/platform/product-branding.json")) {
            when(product.values()).thenReturn(new ObjectMapper().readValue(input, ProductBranding.Values.class));
        }
        var club = new ClubConfig.ClubView("club-a", "club-a", "Example Club", List.of("ca", "es"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        when(configs.get("club-a")).thenReturn(new ClubConfig(club, Map.of(), Set.of(), null, Map.of()));
    }

    @Test void T_14_18_aConsoleRequestNamesThePlatformAndDatesItInTheAccountsLanguageAndThePlatformsZone() {
        account("en");
        notices.deliver("event-1", event(null, "PLATFORM"));
        var variables = sent("en");
        // 22:00 UTC on the 29th is the 30th in the platform's zone (Europe/Madrid), written as English writes a date.
        assertThat(variables).containsEntry("club_name", "AgilityHub").containsEntry("execute_date", "September 30, 2026");
    }

    @Test void T_14_18_aClubRequestNamesTheClubAndFallsBackToItsLanguage() {
        account("es");
        notices.deliver("event-1", event("club-a", "ADMIN"));
        assertThat(sent("es")).containsEntry("club_name", "Example Club").containsEntry("execute_date", "30 de septiembre de 2026");
        // A language the club does not offer: the club's default one.
        reset(notifications); account("en");
        notices.deliver("event-2", event("club-a", "ADMIN"));
        assertThat(sent("ca")).containsEntry("execute_date", "30 de setembre de 2026");
    }

    @Test void T_14_18_aRetentionRequestOrAMissingAccountSendsNothing() {
        account("ca");
        notices.deliver("event-1", event("club-a", "RETENTION"));
        when(accounts.find("montse-account")).thenReturn(Optional.empty());
        notices.deliver("event-2", event(null, "PLATFORM"));
        notices.deliver("event-3", new ErasureForeignEvent("AccountErasureRequested", null, "r", Instant.parse("2026-09-05T08:00:00Z"), Map.of("source", "PLATFORM")));
        verifyNoInteractions(notifications);
    }
}

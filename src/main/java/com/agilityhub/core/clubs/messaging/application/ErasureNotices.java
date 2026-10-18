package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.ClubFormats;
import com.agilityhub.core.platform.application.ProductBranding;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S14 §8 N-52 «Hem rebut la teva sol·licitud de supressió»: on `AccountErasureRequested{source ≠ RETENTION}`, one SYSTEM e-mail
 * to the account (while it exists) with the club's name and the execution date in the club's zone. An account's request (the
 * console's, without a club) names the platform and uses its zone, both from the product's configuration
 * ({@link ProductBranding}, E11-T01 round 2). The date is `fmtDate(long)` in the language the e-mail is written in: the
 * account's, when the club (or the platform) offers it, otherwise the default one. Once per event: the notification id
 * derives from the event id, so a redelivery sends nothing.
 */
@Service
public class ErasureNotices {
    private final SystemNotificationService notifications;
    private final NotificationAccounts accounts;
    private final ClubConfigService configs;
    private final ProductBranding product;
    private final IcuMessageSource messages;
    public ErasureNotices(SystemNotificationService notifications, NotificationAccounts accounts, ClubConfigService configs, ProductBranding product,
            IcuMessageSource messages) {
        this.notifications = notifications; this.accounts = accounts; this.configs = configs; this.product = product; this.messages = messages;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deliver(String eventId, ErasureForeignEvent event) {
        var payload = event.payload();
        if ("RETENTION".equals(payload.get("source")) || !(payload.get("accountId") instanceof String accountId) || payload.get("executeAt") == null) { return; }
        var account = accounts.find(accountId).orElse(null);
        if (account == null) { return; }
        Instant executeAt = Instant.parse(payload.get("executeAt").toString());
        String id = UUID.nameUUIDFromBytes(("N-52:" + eventId).getBytes(StandardCharsets.UTF_8)).toString();
        if (event.clubId() == null) {
            var platform = product.values();
            send(id, account, platform.name(), ZoneId.of(platform.timeZone()), platform.locales(), platform.defaultLocale(), executeAt);
            return;
        }
        try (var tenant = TenantContext.open(event.clubId())) {
            var club = configs.get(event.clubId()).club();
            send(id, account, club.name(), ZoneId.of(club.timeZone()), club.locales(), club.defaultLocale(), executeAt);
        }
    }

    private void send(String id, NotificationAccounts.Recipient account, String name, ZoneId zone, List<String> locales, String defaultLocale, Instant executeAt) {
        String tag = account.locale() != null && locales != null && locales.contains(account.locale()) ? account.locale() : defaultLocale;
        Locale locale = Locale.forLanguageTag(tag);
        if (!messages.supports(locale)) { locale = Locale.forLanguageTag(defaultLocale); tag = defaultLocale; }
        String date = new ClubFormats(zone, messages).formatLongDate(executeAt.atZone(zone).toLocalDate(), locale);
        notifications.sendOnceLocalized(id, "N-52", account.id(), tag, Map.of("execute_date", date, "club_name", name));
    }

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        /** The bean name is the durable outbox consumer id. */
        @Bean DomainEventHandler<ErasureForeignEvent> erasureRequestedNotice(ErasureNotices notices) {
            return new DomainEventHandler<>() {
                public String eventType() { return "AccountErasureRequested"; }
                public Class<ErasureForeignEvent> eventClass() { return ErasureForeignEvent.class; }
                public void handle(String eventId, ErasureForeignEvent event) { notices.deliver(eventId, event); }
            };
        }
    }
}

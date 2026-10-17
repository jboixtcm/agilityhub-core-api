package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S14 §8 N-52 «Hem rebut la teva sol·licitud de supressió»: on `AccountErasureRequested{source ≠ RETENTION}`, one SYSTEM e-mail
 * to the account (while it exists) with the execution date in the club's zone and the club's name (an account's request, without
 * a club, names the platform). Once per event: the notification id derives from the event id, so a redelivery sends nothing.
 */
@Service
public class ErasureNotices {
    static final String PLATFORM = "AgilityHub";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private final SystemNotificationService notifications;
    private final ClubConfigService configs;
    public ErasureNotices(SystemNotificationService notifications, ClubConfigService configs) { this.notifications = notifications; this.configs = configs; }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deliver(String eventId, ErasureForeignEvent event) {
        var payload = event.payload();
        if ("RETENTION".equals(payload.get("source")) || !(payload.get("accountId") instanceof String accountId) || payload.get("executeAt") == null) { return; }
        Instant executeAt = Instant.parse(payload.get("executeAt").toString());
        String id = UUID.nameUUIDFromBytes(("N-52:" + eventId).getBytes(StandardCharsets.UTF_8)).toString();
        if (event.clubId() == null) {
            notifications.sendOnce(id, "N-52", accountId, Map.of("execute_date", DATE.format(executeAt.atZone(ZoneId.of("UTC"))), "club_name", PLATFORM));
            return;
        }
        try (var tenant = TenantContext.open(event.clubId())) {
            var club = configs.get(event.clubId()).club();
            notifications.sendOnce(id, "N-52", accountId, Map.of("execute_date", DATE.format(executeAt.atZone(ZoneId.of(club.timeZone()))), "club_name", club.name()));
        }
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

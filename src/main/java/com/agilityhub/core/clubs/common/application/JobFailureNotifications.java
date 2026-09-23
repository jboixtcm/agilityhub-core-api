package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;

import com.agilityhub.core.identity.application.SignupIdentityService;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S15 R-15-10 → N-42 «Procés automàtic amb errors» to the club ADMINS (APP + EMAIL) when `jobs.alertAdminsOnFailure`.
 * At most one per process and club-local day: the notification ids are derived from job, local date and account,
 * so a second JobFailed of the same day finds them already delivered.
 */
@Service
public class JobFailureNotifications {
    static final String CODE = "N-42";
    private final SignupIdentityService identities;
    private final NotificationAccounts accounts;
    private final SystemNotificationService notifications;
    private final ClubConfigService configs;
    private final IcuMessageSource messages;
    public JobFailureNotifications(SignupIdentityService identities, NotificationAccounts accounts, SystemNotificationService notifications,
            ClubConfigService configs, IcuMessageSource messages) {
        this.identities = identities; this.accounts = accounts; this.notifications = notifications; this.configs = configs; this.messages = messages;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deliver(SchedulerEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            var config = configs.get(event.clubId());
            if (!Boolean.TRUE.equals(config.get("jobs.alertAdminsOnFailure", Boolean.class))) { return; }
            String job = event.payload().get("job").toString();
            var day = event.occurredAt().atZone(configs.timeZone(event.clubId())).toLocalDate();
            for (String admin : identities.admins()) {
                var account = accounts.find(admin).orElse(null);
                if (account == null) { continue; }
                Locale locale = locale(account.locale(), config.club().defaultLocale());
                var variables = new LinkedHashMap<String, Object>();
                variables.put("club_name", config.club().name());
                variables.put("job_name", messages.getMessage("jobs.name." + job, null, job, locale));
                variables.put("error_count", ((Number) event.payload().getOrDefault("errorCount", 0)).intValue());
                variables.put("entityId", event.aggregateId());
                variables.put("action", "OPEN_JOBS");
                String key = CODE + ":" + job + ":" + day + ":" + admin;
                notifications.appOnce(key + ":app", CODE, admin, variables);
                if (account.email() != null) { notifications.sendOnce(key + ":email", CODE, admin, variables); }
            }
        }
    }
    private static Locale locale(String tag, String fallback) {
        return Locale.forLanguageTag(tag != null && Set.of("ca", "es", "en").contains(tag) ? tag : fallback);
    }

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        @Bean("notifications.N-42") DomainEventHandler<SchedulerEvent> jobFailed(JobFailureNotifications service) {
            return new DomainEventHandler<>() {
                @Override public String eventType() { return "JobFailed"; }
                @Override public Class<SchedulerEvent> eventClass() { return SchedulerEvent.class; }
                @Override public void handle(String id, SchedulerEvent event) { service.deliver(event); }
            };
        }
    }
}

package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.census.application.SchedulingRecipients;
import com.agilityhub.core.clubs.messaging.application.NotificationFanout;
import com.agilityhub.core.clubs.scheduling.application.WeekOpenings;
import com.agilityhub.core.clubs.common.domain.ForeignEvent;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import com.ibm.icu.text.DateFormat;
import com.ibm.icu.util.TimeZone;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S15 R-15-11 / §8 N-33 «Ja pots reservar la setmana vinent» to every ACTIVE member with an ACTIVE dog (APP + PUSH,
 * `week_start` = the ISO Monday in the recipient's language, action OPEN_BOOKING), through the batched
 * {@link NotificationFanout}. Two entry points, one set of deterministic ids (`N-33:{weekId}:{accountId}`):
 * `WeekOpened{notified: true}` (P1 already set `openingNotifiedAt`), and the deferred case — `WeekValidated` of a week whose
 * booking has opened (`now ≥ Week.openedAt`) and was not notified yet.
 */
@Service
public class WeekOpeningNotifications {
    static final String CODE = "N-33";
    private final WeekOpenings weeks; private final SchedulingRecipients recipients; private final NotificationAccounts accounts;
    private final NotificationFanout fanout; private final ClubConfigService configs; private final Clock clock;
    public WeekOpeningNotifications(WeekOpenings weeks, SchedulingRecipients recipients, NotificationAccounts accounts, NotificationFanout fanout,
            ClubConfigService configs, Clock clock) {
        this.weeks = weeks; this.recipients = recipients; this.accounts = accounts; this.fanout = fanout; this.configs = configs; this.clock = clock;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int opened(SchedulerEvent event) {
        if (!Boolean.TRUE.equals(event.payload().get("notified"))) { return 0; }
        try (var tenant = TenantContext.open(event.clubId())) {
            var target = weeks.find(LocalDate.parse(event.payload().get("isoWeekStart").toString())).orElse(null);
            return target == null ? 0 : send(target);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int validated(ForeignEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            var config = configs.get(event.clubId());
            if (!Boolean.TRUE.equals(config.get("messaging.notifyWeekOpening", Boolean.class))) { return 0; }
            var target = weeks.byId(event.aggregateId()).orElse(null);
            if (target == null || target.openedAt() == null || clock.instant().isBefore(target.openedAt()) || target.openingNotifiedAt() != null
                    || target.activeClasses() == 0) { return 0; }
            // Rows first (idempotent ids), then the mark: a redelivery after a crash re-sends nothing and still marks.
            int sent = send(target);
            weeks.markNotified(target.weekId());
            return sent;
        }
    }

    private int send(WeekOpenings.Target target) {
        var config = configs.get(TenantContext.require());
        var rows = new ArrayList<NotificationFanout.Row>();
        for (var member : recipients.activeWithActiveDog()) {
            if (member.accountId() == null) { continue; }
            var account = accounts.find(member.accountId()).orElse(null);
            if (account == null) { continue; }
            String language = account.locale() != null && Set.of("ca", "es", "en").contains(account.locale()) ? account.locale() : config.club().defaultLocale();
            var variables = new LinkedHashMap<String, Object>();
            variables.put("club_name", config.club().name()); variables.put("week_start", weekStart(target.isoWeekStart(), Locale.forLanguageTag(language)));
            variables.put("entityId", target.weekId()); variables.put("action", "OPEN_BOOKING");
            rows.add(new NotificationFanout.Row(CODE + ":" + target.weekId() + ":" + account.id(), account.id(), language, variables));
        }
        int written = fanout.app(CODE, rows);
        fanout.push(CODE, rows.stream().map(row -> new NotificationFanout.Row(row.id() + ":push", row.accountId(), row.locale(), row.variables())).toList(),
                config.modules().contains(Module.PUSH));
        return written;
    }
    /** «12 d'octubre» / «12 de octubre» / «October 12»: day and month of the ISO Monday in the recipient's language. */
    static String weekStart(LocalDate monday, Locale locale) {
        var format = DateFormat.getInstanceForSkeleton("dMMMM", locale); format.setTimeZone(TimeZone.GMT_ZONE);
        return format.format(Date.from(monday.atStartOfDay().toInstant(ZoneOffset.UTC)));
    }

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        @Bean("notifications.N-33") DomainEventHandler<SchedulerEvent> weekOpened(WeekOpeningNotifications service) {
            return new DomainEventHandler<>() {
                @Override public String eventType() { return "WeekOpened"; }
                @Override public Class<SchedulerEvent> eventClass() { return SchedulerEvent.class; }
                @Override public void handle(String id, SchedulerEvent event) { service.opened(event); }
            };
        }
        @Bean("notifications.N-33.deferred") DomainEventHandler<ForeignEvent> weekValidated(WeekOpeningNotifications service) {
            return new DomainEventHandler<>() {
                @Override public String eventType() { return "WeekValidated"; }
                @Override public Class<ForeignEvent> eventClass() { return ForeignEvent.class; }
                @Override public void handle(String id, ForeignEvent event) { service.validated(event); }
            };
        }
    }
}

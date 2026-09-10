package com.agilityhub.core.clubs.dashboard.application;

import com.agilityhub.core.clubs.dashboard.application.DashboardData.*;
import com.agilityhub.core.clubs.dashboard.application.ports.*;
import com.agilityhub.core.clubs.dashboard.domain.*;
import com.agilityhub.core.clubs.dashboard.persistence.DashboardRepository;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.github.benmanes.caffeine.cache.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

@Service
public class DashboardQuery {
    private record CachedDay(LocalDate today, String defaultLocale, Map<String, Snapshot> locales) { }
    private record CounterKey(String clubId, String accountId) { }
    private final Cache<String, CachedDay> dashboards;
    private final Cache<CounterKey, Counters> counters;
    private final ClubConfigService configs;
    private final ClubClock clubClock;
    private final Clock clock;
    private final DashboardRepository repository;
    private final DogActivityQuery dogs;
    private final ClassOccupancyQuery occupancy;
    private final TrainingBookingsQuery training;
    private final ClassSessionsQuery sessions;
    private final RiskCardBuilder risk;
    private final PendingRequestsQuery requests;
    private final FollowUpUnreadQuery followUp;

    public DashboardQuery(ClubConfigService configs, ClubClock clubClock, Clock clock, DashboardRepository repository,
            DogActivityQuery dogs, ClassOccupancyQuery occupancy, TrainingBookingsQuery training, ClassSessionsQuery sessions,
            RiskEvaluator evaluator, PendingRequestsQuery requests, FollowUpUnreadQuery followUp,
            @Value("${app.dashboard.cacheSeconds:60}") long cacheSeconds) {
        this.configs = configs; this.clubClock = clubClock; this.clock = clock; this.repository = repository;
        this.dogs = dogs; this.occupancy = occupancy; this.training = training; this.sessions = sessions;
        this.risk = new RiskCardBuilder(evaluator); this.requests = requests; this.followUp = followUp;
        dashboards = Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(Duration.ofSeconds(cacheSeconds))
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis())).build();
        counters = Caffeine.newBuilder().maximumSize(20000).expireAfterWrite(Duration.ofSeconds(30))
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis())).build();
    }
    public Snapshot get(String locale) {
        guard(); String club = TenantContext.require(); LocalDate today = clubClock.today(club);
        var cached = dashboards.get(club, key -> load(key, today));
        if (!cached.today().equals(today)) { dashboards.invalidate(club); cached = dashboards.get(club, key -> load(key, today)); }
        return cached.locales().getOrDefault(locale, cached.locales().get(cached.defaultLocale()));
    }
    public Counters counters() {
        guard(); String club = TenantContext.require(); var user = CurrentUser.current();
        return counters.get(new CounterKey(club, user.accountId()), key -> {
            var config = configs.get(key.clubId()); var pending = requests.counts(key.clubId());
            return new Counters(repository.pendingCount(), pending.leaves() + (config.modules().contains(Module.INACTIVITY) ? pending.inactivity() : 0),
                    followUp.count(key.clubId(), key.accountId()));
        });
    }
    private static void guard() {
        var user = CurrentUser.current();
        if (user != null && user.impersonation() != null) { throw new ApiException(ErrorCode.IMPERSONATION_DENIED); }
        if (user == null) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
    }
    private CachedDay load(String club, LocalDate today) {
        var config = configs.get(club); var period = new DashboardPeriod(today, ZoneId.of(config.club().timeZone()));
        Instant generated = clock.instant();
        int weeks = config.get("coverage.activeDogWeeks", Integer.class);
        int lookahead = config.get("classes.riskLookaheadDays", Integer.class);
        int warnDays = config.get("dashboard.pendingSignupAgeWarnDays", Integer.class);
        LocalTime reviewTime = LocalTime.parse(config.get("classes.riskReviewTime", String.class));
        boolean autoCancel = config.get("classes.riskAutoCancelSameDay", Boolean.class);
        boolean levels = config.get("levels.enabled", Boolean.class);
        var members = repository.activeMembers(period); var pending = repository.pendingSignups();
        var classKpi = KpiBuilder.occupancy(occupancy.sessions(club, period.from(), period.until()), period, config.modules().contains(Module.WAITLIST));
        var trainingKpi = config.modules().contains(Module.FREE_TRAINING)
                ? KpiBuilder.training(training.bookings(club, period.from(), period.until()), period) : null;
        var classes = sessions.sessions(club, today, today.plusDays(lookahead));
        var levelRows = levels ? repository.levels() : List.<LevelSource>of();
        var dogCounts = levels ? dogs.counts(today, period.zone(), weeks) : List.<DogCount>of();
        var variants = new LinkedHashMap<String, Snapshot>();
        var locales = new LinkedHashSet<>(config.club().locales()); locales.add(config.club().defaultLocale());
        for (String locale : locales) {
            var signups = PendingSignupBuilder.build(pending, period, warnDays, config.modules().contains(Module.BILLING),
                    config.modules().contains(Module.FAMILY_GROUP), locale, config.club().defaultLocale());
            variants.put(locale, new Snapshot(generated, today, new Week(period.weekStart(), period.weekEnd()),
                    new Kpis(members, classKpi, trainingKpi, signups.kpi()),
                    risk.build(classes, period, lookahead, reviewTime, autoCancel, locale, config.club().defaultLocale()), signups.card(),
                    levels ? DogActivityBuilder.build(levelRows, dogCounts, weeks, locale, config.club().defaultLocale()) : null));
        }
        return new CachedDay(today, config.club().defaultLocale(), Map.copyOf(variants));
    }
    public void invalidate(String clubId) {
        if (clubId == null) { return; }
        dashboards.invalidate(clubId);
        counters.asMap().keySet().removeIf(key -> key.clubId().equals(clubId));
    }
    public void invalidateAfterCommit(String clubId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate(clubId); }
            });
        } else { invalidate(clubId); }
    }
}

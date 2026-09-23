package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.jobs.JobLockRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * S15 R-15-01: second 0 of every minute, clubs one after another, processes in the fixed catalog order.
 * The `tick` lease (55 s, never released early) keeps one active tick per minute across instances;
 * a tick that finds it held is skipped and counted in `jobs.tick.overrun`.
 */
@Component
public class SchedulerTick {
    static final String LOCK = "tick";
    static final Duration LEASE = Duration.ofSeconds(55);
    private static final Logger LOG = LoggerFactory.getLogger(SchedulerTick.class);
    private final JobRunner runner;
    private final ClubRepository clubs;
    private final JobLockRepository locks;
    private final JobMetrics metrics;
    private final Clock clock;
    private final String instance = UUID.randomUUID().toString();

    public SchedulerTick(JobRunner runner, ClubRepository clubs, JobLockRepository locks, JobMetrics metrics, Clock clock) {
        this.runner = runner; this.clubs = clubs; this.locks = locks; this.metrics = metrics; this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *")
    public void scheduled() { run(clock.instant()); }

    /** Returns false when another tick holds the lease. */
    public boolean run(Instant instant) {
        Instant now = instant.truncatedTo(ChronoUnit.MINUTES);
        if (!locks.acquire(LOCK, instance, now, LEASE)) { metrics.tickOverrun(); return false; }
        var jobs = runner.registered();
        for (Club club : clubs.schedulableClubs()) {
            for (Job job : jobs) {
                try { runner.scheduled(club.id(), club.status() != Club.Status.SUSPENDED, job, now); }
                catch (RuntimeException failure) {
                    // Isolate one club's failure so the rest of the clubs and processes still run.
                    LOG.error("Scheduler tick failed job={} clubId={}", job.name(), club.id(), failure);
                }
            }
        }
        return true;
    }
}

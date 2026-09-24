package com.agilityhub.core.platform.application.jobs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** S15 R-15-10 Micrometer names. */
@Component
public class JobMetrics {
    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<String, Instant> lastSuccess = new ConcurrentHashMap<>();
    private final Counter overrun;
    public JobMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry; this.clock = clock;
        this.overrun = Counter.builder("jobs.tick.overrun").description("Minute ticks skipped because the previous tick was still running").register(registry);
    }
    /** R-15-01: a tick that lasted past the next minute's tick counts every minute tick it made the scheduler skip. */
    public void tickOverrun(long skippedTicks) { overrun.increment(skippedTicks); }
    public void finished(String clubId, JobName job, JobStatus status, Duration duration, Map<String, Long> counters) {
        Timer.builder("jobs.run.duration").tag("job", job.name()).tag("status", status.name()).register(registry).record(duration);
        counters.forEach((key, value) -> Counter.builder("jobs.effects").tag("job", job.name()).tag("key", key).register(registry).increment(value));
        if (status == JobStatus.SUCCEEDED) {
            String key = job.name() + "|" + clubId;
            if (lastSuccess.put(key, clock.instant()) == null) {
                Gauge.builder("jobs.last_success_age_seconds", lastSuccess,
                                values -> Duration.between(values.get(key), clock.instant()).toSeconds())
                        .tag("job", job.name()).tag("club", clubId).register(registry);
            }
        }
    }
}

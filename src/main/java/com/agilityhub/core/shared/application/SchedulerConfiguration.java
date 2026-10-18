package com.agilityhub.core.shared.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * INC-40 (E11-T10): one single-thread scheduler per loop, named by each `@Scheduled(scheduler = …)`, so a slow provider cannot
 * hold another loop: the outbox dispatcher (every second), the S15 job tick (R-15-01, second 0 of every minute) and the S11
 * notification poll (R-11-09, every 5 s, the SendGrid/Twilio/push sends, and the inline sends after a commit, E13). The exports
 * keep their `exportScheduler`; any other unqualified loop (payment recovery) uses the default `taskScheduler`.
 */
@Configuration(proxyBeanMethods = false)
public class SchedulerConfiguration {
    public static final String OUTBOX = "outboxScheduler", JOB_TICK = "jobTickScheduler", NOTIFICATIONS = "notificationScheduler";

    @Bean(name = OUTBOX) ThreadPoolTaskScheduler outboxScheduler() { return single("outbox-dispatcher-"); }
    @Bean(name = JOB_TICK) ThreadPoolTaskScheduler jobTickScheduler() { return single("job-tick-"); }
    @Bean(name = NOTIFICATIONS) ThreadPoolTaskScheduler notificationScheduler() { return single("notification-sender-"); }

    static ThreadPoolTaskScheduler single(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix(prefix); return scheduler;
    }
}

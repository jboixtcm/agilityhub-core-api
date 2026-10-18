package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.messaging.application.engine.NotificationDispatcher;
import com.agilityhub.core.platform.application.jobs.SchedulerTick;
import com.agilityhub.core.shared.application.OutboxDispatcher;
import com.agilityhub.core.shared.application.SchedulerConfiguration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.SchedulingAwareRunnable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * INC-40 (E11-T10): the outbox dispatcher, the S15 job tick (R-15-01, second 0 of every minute) and the S11 notification poll
 * (R-11-09, every 5 s, with the provider sends) each run on their own single-thread scheduler, so a stalled provider send
 * delays neither the outbox nor the tick.
 */
class SchedulerSeparationTest {
    @Configuration(proxyBeanMethods = false) @EnableScheduling static class Enabled { }

    /** R-15-01, R-11-09, INC-40 (E11-T10): each loop is routed to its own scheduler; a blocked notification poll holds neither the outbox nor the tick. */
    @Test void R_15_01_R_11_09_INC_40_theOutboxTheTickAndTheNotificationPollRunOnTheirOwnSchedulers() throws Exception {
        var outbox = mock(OutboxDispatcher.class); var tick = mock(SchedulerTick.class); var poll = mock(NotificationDispatcher.class);
        var threads = new ConcurrentHashMap<String, String>();
        var release = new CountDownLatch(1); var polling = new CountDownLatch(1); var dispatched = new CountDownLatch(3);
        doAnswer(invocation -> { threads.put("poll", Thread.currentThread().getName()); polling.countDown(); release.await(10, TimeUnit.SECONDS); return 0; }).when(poll).poll();
        doAnswer(invocation -> { threads.put("outbox", Thread.currentThread().getName()); dispatched.countDown(); return null; }).when(outbox).dispatch();
        new ApplicationContextRunner().withUserConfiguration(Enabled.class, SchedulerConfiguration.class)
                .withPropertyValues("notifications.dispatcher.poll-millis=20")
                .withBean("taskScheduler", ThreadPoolTaskScheduler.class, ThreadPoolTaskScheduler::new)
                // The poll's one field-injected collaborator (it is never called here).
                .withBean(com.agilityhub.core.shared.application.SignupCapabilities.class, () -> mock(com.agilityhub.core.shared.application.SignupCapabilities.class))
                .withBean(OutboxDispatcher.class, () -> outbox).withBean(SchedulerTick.class, () -> tick).withBean(NotificationDispatcher.class, () -> poll)
                .run(context -> {
                    var qualifiers = new java.util.HashMap<String, String>();
                    for (var task : context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()) {
                        // Spring wraps the method runnable (outcome tracking); the wrapper keeps its qualifier and its `Class.method` name.
                        if (task.getTask().getRunnable() instanceof SchedulingAwareRunnable method) {
                            String name = method.toString(); qualifiers.put(name.substring(name.lastIndexOf('.') + 1), method.getQualifier());
                        }
                    }
                    assertThat(qualifiers).containsAllEntriesOf(Map.of("dispatch", SchedulerConfiguration.OUTBOX, "scheduled", SchedulerConfiguration.JOB_TICK,
                            "poll", SchedulerConfiguration.NOTIFICATIONS));
                    var schedulers = Map.of(SchedulerConfiguration.OUTBOX, "outbox-dispatcher-", SchedulerConfiguration.JOB_TICK, "job-tick-",
                            SchedulerConfiguration.NOTIFICATIONS, "notification-sender-");
                    schedulers.forEach((name, prefix) -> {
                        var scheduler = context.getBean(name, ThreadPoolTaskScheduler.class);
                        assertThat(scheduler.getPoolSize()).as(name).isEqualTo(1);
                        assertThat(scheduler.getThreadNamePrefix()).as(name).isEqualTo(prefix);
                    });
                    // The poll is stuck on a provider: the outbox keeps dispatching and the tick's scheduler still runs its work.
                    assertThat(polling.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(dispatched.await(10, TimeUnit.SECONDS)).as("outbox dispatches while the poll is blocked").isTrue();
                    var ticked = new CountDownLatch(1);
                    context.getBean(SchedulerConfiguration.JOB_TICK, ThreadPoolTaskScheduler.class).execute(() -> { threads.put("tick", Thread.currentThread().getName()); ticked.countDown(); });
                    assertThat(ticked.await(10, TimeUnit.SECONDS)).as("tick runs while the poll is blocked").isTrue();
                    release.countDown();
                    assertThat(threads.get("poll")).startsWith("notification-sender-");
                    assertThat(threads.get("outbox")).startsWith("outbox-dispatcher-");
                    assertThat(threads.get("tick")).startsWith("job-tick-");
                });
    }
}

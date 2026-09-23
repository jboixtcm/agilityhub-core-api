package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.CoreApplication;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.MutableClock;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.assertThat;

/** T-15-31 (R-15-06): two application contexts over the same Mongo with the same time. */
class JobFrameworkTwoInstancesIT extends AbstractIntegrationTest {
    static final String CLUB = "e5-jobs-twin";
    static ConfigurableApplicationContext second;
    @Autowired SchedulerTick tick;
    @Autowired JobRunner runner;
    @Autowired TestNoopJob job;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired ObjectMapper mapper;

    @BeforeEach void prepare() {
        if (second == null) {
            // Command-line arguments outrank application.yml, as the DynamicPropertySource of the first context does.
            second = new SpringApplicationBuilder(CoreApplication.class).profiles("test")
                    .run("--spring.data.mongodb.uri=" + MONGO.getReplicaSetUrl("agilityhub_test"), "--core.oidc.master-key=" + OIDC_MASTER,
                            "--shared.scheduling.enabled=false", "--server.port=0", "--management.server.port=0");
        }
        mongo.remove(Query.query(Criteria.where("_id").is(CLUB)), Club.class);
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "job_runs");
        mongo.remove(new Query(), "job_locks");
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(CLUB, CLUB + ".example.test"));
        tree.set("modules", mapper.valueToTree(List.of(Module.BILLING)));
        clubs.save(mapper.convertValue(tree, Club.class));
        configs.invalidate(CLUB); second.getBean(ClubConfigService.class).invalidate(CLUB);
        job.configure(TestNoopJob.DEFAULT, 1, 0, false);
        second.getBean(TestNoopJob.class).configure(TestNoopJob.DEFAULT, 1, 0, false);
    }
    @AfterAll static void close() { if (second != null) { second.close(); second = null; } }

    private <T> List<T> race(Callable<T> first, Callable<T> other) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return first.call(); });
            var b = pool.submit(() -> { start.await(); return other.call(); });
            start.countDown();
            return List.of(a.get(), b.get());
        } finally { pool.shutdownNow(); }
    }
    private long executed(Instant scheduledFor) {
        return mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("job").is("TEST_NOOP").and("scheduledFor").is(scheduledFor)
                .and("status").ne("SKIPPED")), "job_runs");
    }

    @Test void T_15_31_twoInstancesKeepOneTickPerMinuteAndOneExecutionPerOccurrence() throws Exception {
        Instant first = Instant.parse("2026-10-05T04:00:00Z");
        clock.setInstant(first);
        second.getBean(MutableClock.class).setInstant(first);
        var ticks = race(() -> tick.run(first), () -> second.getBean(SchedulerTick.class).run(first));
        assertThat(ticks).containsExactlyInAnyOrder(true, false);
        assertThat(executed(first)).isEqualTo(1);
        // Bypassing the tick lease, the two runners race on the same occurrence: lease + unique index leave one execution.
        Instant next = Instant.parse("2026-10-06T04:00:00Z");
        clock.setInstant(next);
        second.getBean(MutableClock.class).setInstant(next);
        var otherJob = second.getBean(TestNoopJob.class);
        job.configure(TestNoopJob.DEFAULT, 1, 0, false);
        otherJob.configure(TestNoopJob.DEFAULT, 1, 0, false);
        race(() -> runner.scheduled(CLUB, true, job, next), () -> second.getBean(JobRunner.class).scheduled(CLUB, true, otherJob, next));
        assertThat(executed(next)).isEqualTo(1);
        assertThat(job.applied() + otherJob.applied()).isEqualTo(1);
    }
}

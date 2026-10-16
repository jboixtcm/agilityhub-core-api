package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.BuildSessionService;
import com.agilityhub.core.courses.domain.CourseParts.BuildObstacle;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.persistence.BuildSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E9-T06 (review of E9-T04 #5–#7): the live streams of R-16-11 with fake clients. A client that never reads blocks only its own
 * send: another subscriber of the same session still gets each change in under R-16-11's 2 s, and the stalled one is closed once
 * its write times out or its queue fills. A stream lives no longer than its token, and an outlived session ends its streams.
 */
@org.junit.jupiter.api.Timeout(value = 30, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
class BuildSessionStreamsTest {
    static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");
    static final Duration LIVE = Duration.ofSeconds(2);
    final BuildSessionService service = mock(BuildSessionService.class);
    final BuildSessionStreams streams = new BuildSessionStreams(service, new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC));

    /** Every stalled client, released after each test so that no thread stays blocked (a mutant that sends on the caller's thread). */
    final List<Stalled> stalled = new java.util.concurrent.CopyOnWriteArrayList<>();

    @AfterEach void stop() { stalled.forEach(client -> client.release.countDown()); streams.destroy(); }

    static BuildSession session(long version, BuildStatus status, ObstacleStatus obstacle) {
        return new BuildSession("session-a", "club-a", "placement-a", "ring-a", status, BuildStrategy.EQUIPMENT_TYPE, true, "account-a", NOW, null, null,
                null, 0, null, "ABC234", "fictional-token", NOW, List.of(new BuildObstacle("obstacle-a", obstacle, "account-a", NOW, null)), List.of(), null,
                version, NOW, "account-a", NOW, "account-a");
    }

    /** A client that reads every frame, as it arrives. */
    static class Reader extends SseEmitter {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        volatile boolean completed;
        Reader() { super(0L); }
        @Override public void send(SseEventBuilder builder) throws IOException {
            frames.add(builder.build().stream().map(part -> part.getData().toString()).collect(Collectors.joining()));
        }
        @Override public void complete() { completed = true; }
        String next(String text) throws InterruptedException {
            long deadline = System.nanoTime() + LIVE.toNanos();
            for (String frame; (frame = frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) != null; ) {
                if (frame.contains(text)) { return frame; }
            }
            throw new AssertionError("No frame with «" + text + "» within " + LIVE);
        }
    }
    /** A client that stops reading after the first frame: every later send blocks (a full TCP buffer) until released. */
    class Stalled extends Reader {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger sends = new AtomicInteger();
        Stalled() { stalled.add(this); }
        @Override public void send(SseEventBuilder builder) throws IOException {
            if (sends.incrementAndGet() > 1) {
                try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            super.send(builder);
        }
    }
    static void eventually(Runnable check) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try { check.run(); return; }
            catch (AssertionError notYet) { if (System.nanoTime() > deadline) { throw notYet; } Thread.sleep(20); }
        }
    }

    /**
     * Review #5: two phones on one session, one of which stops reading. Each of two changes still reaches the other phone in
     * under 2 s (on one shared sending thread, the first blocked send would hold the second change back), and the stalled stream
     * is closed once its send has been blocked for the write timeout; it completes when that send returns.
     */
    @Test void T_16_10_aClientThatDoesNotReadDelaysNoOtherStream() throws Exception {
        streams.writeTimeout(Duration.ofMillis(300));
        var stalled = new Stalled(); var reader = new Reader();
        streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), stalled);
        streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), reader);
        reader.next(":open");
        assertThat(streams.subscribers()).isEqualTo(2);
        int version = 0;
        for (var status : List.of(ObstacleStatus.PLACED, ObstacleStatus.NEEDS_CHECK)) {
            when(service.current("session-a")).thenReturn(Optional.of(session(++version, BuildStatus.IN_PROGRESS, status)));
            long changed = System.nanoTime();
            streams.changed(new BuildSessionService.Changed("club-a", "session-a"));
            assertThat(reader.next("event:obstacle")).contains("\"status\":\"" + status + "\"");
            assertThat(Duration.ofNanos(System.nanoTime() - changed)).isLessThan(LIVE);
        }
        eventually(() -> assertThat(streams.subscribers()).as("the stalled stream left its session").isEqualTo(1));
        assertThat(stalled.completed).isFalse();
        stalled.release.countDown();
        eventually(() -> assertThat(stalled.completed).as("completed once its blocked send returned").isTrue());
        assertThat(reader.completed).isFalse();
    }

    /** Review #5: a client whose queue fills up (more than {@link BuildSessionStreams#QUEUE} frames waiting) is closed at once. */
    @Test void T_16_10_aClientWhoseQueueFillsIsClosed() throws Exception {
        var stalled = new Stalled(); var reader = new Reader();
        streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), stalled);
        streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), reader);
        for (int i = 0; i <= BuildSessionStreams.QUEUE + 1; i++) { streams.heartbeat(); }
        assertThat(streams.subscribers()).isEqualTo(1);
        assertThat(reader.next(":heartbeat")).isNotEmpty();
        stalled.release.countDown();
        eventually(() -> assertThat(stalled.completed).isTrue());
    }

    /** Review #6: the stream's timeout is the smaller of 30 minutes and what is left of the access token. */
    @Test void T_16_10_aStreamLivesNoLongerThanItsToken() {
        assertThat(BuildSessionStreams.lifetime(NOW, null)).isEqualTo(BuildSessionStreams.TIMEOUT);
        assertThat(BuildSessionStreams.lifetime(NOW, NOW.plus(Duration.ofMinutes(15)))).isEqualTo(Duration.ofMinutes(15));
        assertThat(BuildSessionStreams.lifetime(NOW, NOW.plus(Duration.ofHours(1)))).isEqualTo(Duration.ofMinutes(30));
        assertThat(BuildSessionStreams.lifetime(NOW, NOW.plusSeconds(3))).isEqualTo(Duration.ofSeconds(3));
        assertThat(BuildSessionStreams.lifetime(NOW, NOW.minusSeconds(1))).isEqualTo(Duration.ofMillis(1));
        assertThat(streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), NOW.plusSeconds(3)).getTimeout()).isEqualTo(3000L);
        assertThat(streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), (Instant) null).getTimeout())
                .isEqualTo(BuildSessionStreams.TIMEOUT.toMillis());
    }

    /**
     * Review #7: once the session has outlived `courses.buildSessionMaxHours` (still `IN_PROGRESS`, P9 has not run), the next
     * refresh sends `finished {ABANDONED, finishedAt: null}` and ends the stream; a stream opened on it gets the same at once.
     */
    @Test void T_16_10_anOutlivedSessionEndsItsStreamsBeforeP9() throws Exception {
        var reader = new Reader();
        var open = session(1, BuildStatus.IN_PROGRESS, ObstacleStatus.PLACED);
        streams.subscribe(open, reader);
        reader.next(":open");
        when(service.current("session-a")).thenReturn(Optional.of(open));
        when(service.over(any())).thenReturn(true);
        streams.changed(new BuildSessionService.Changed("club-a", "session-a"));
        String finished = reader.next("event:finished");
        assertThat(finished).contains("\"status\":\"ABANDONED\"", "\"finishedAt\":null", "\"version\":1");
        eventually(() -> assertThat(reader.completed).isTrue());
        assertThat(streams.subscribers()).isZero();
        var late = new Reader();
        streams.subscribe(open, late);
        assertThat(late.next("event:finished")).contains("\"status\":\"ABANDONED\"");
        eventually(() -> assertThat(late.completed).isTrue());
        assertThat(streams.subscribers()).isZero();
    }
}

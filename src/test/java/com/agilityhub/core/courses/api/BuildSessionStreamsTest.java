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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
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
 * Round 2: the queue test and the sender cap run without the timer and wait on latches only, never on the runner's speed.
 */
@org.junit.jupiter.api.Timeout(value = 30, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
class BuildSessionStreamsTest {
    static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");
    static final Duration LIVE = Duration.ofSeconds(2);
    /** The most a latch may wait before the test fails; no outcome depends on waiting less. */
    static final Duration WAIT = Duration.ofSeconds(10);
    final BuildSessionService service = mock(BuildSessionService.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final BuildSessionStreams streams = new BuildSessionStreams(service, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    final List<BuildSessionStreams> quiet = new ArrayList<>();

    /** Every stalled client, released after each test so that no thread stays blocked (a mutant that sends on the caller's thread). */
    final List<Stalled> stalled = new java.util.concurrent.CopyOnWriteArrayList<>();

    @AfterEach void stop() {
        stalled.forEach(client -> client.release.countDown());
        streams.destroy(); quiet.forEach(BuildSessionStreams::destroy);
    }

    /** Streams with {@code senders} sends at once and no heartbeat or poll of their own: only what the test calls is sent. */
    BuildSessionStreams quiet(int senders) {
        var created = new BuildSessionStreams(service, mapper, Clock.fixed(NOW, ZoneOffset.UTC), senders, false);
        quiet.add(created);
        return created;
    }

    static BuildSession session(long version, BuildStatus status, ObstacleStatus obstacle) {
        return new BuildSession("session-a", "club-a", "placement-a", "ring-a", status, BuildStrategy.EQUIPMENT_TYPE, true, "account-a", NOW, null, null,
                null, 0, null, "ABC234", "fictional-token", NOW, List.of(new BuildObstacle("obstacle-a", obstacle, "account-a", NOW, null)), List.of(), null,
                version, NOW, "account-a", NOW, "account-a");
    }
    static void await(CountDownLatch latch, String what) throws InterruptedException {
        assertThat(latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).as(what).isTrue();
    }

    /** A client that reads every frame, as it arrives. */
    static class Reader extends SseEmitter {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean completed;
        Reader() { super(0L); }
        @Override public void send(SseEventBuilder builder) throws IOException {
            frames.add(builder.build().stream().map(part -> part.getData().toString()).collect(Collectors.joining()));
        }
        @Override public void complete() { completed = true; done.countDown(); }
        String next(String text) throws InterruptedException { return next(text, LIVE); }
        String next(String text, Duration within) throws InterruptedException {
            long deadline = System.nanoTime() + within.toNanos();
            for (String frame; (frame = frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) != null; ) {
                if (frame.contains(text)) { return frame; }
            }
            throw new AssertionError("No frame with «" + text + "» within " + within);
        }
    }
    /** A client that stops reading after {@code free} frames: every later send blocks (a full TCP buffer) until released. */
    class Stalled extends Reader {
        final CountDownLatch release = new CountDownLatch(1);
        /** Counted down when a send starts to block. */
        final CountDownLatch blocked = new CountDownLatch(1);
        final AtomicInteger sends = new AtomicInteger();
        final int free;
        Stalled() { this(1); }
        Stalled(int free) { this.free = free; stalled.add(this); }
        @Override public void send(SseEventBuilder builder) throws IOException {
            if (sends.incrementAndGet() > free) {
                blocked.countDown();
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
        await(stalled.done, "completed once its blocked send returned");
        assertThat(reader.completed).isFalse();
    }

    /**
     * Review #5, round 2 (E120: the CI run of `9094895` failed it): a client whose send is blocked keeps its stream while exactly
     * {@link BuildSessionStreams#QUEUE} frames wait, and the next frame closes it at once; the phone that reads keeps its stream.
     * No timer runs and every step waits on a latch (the blocked send, each frame read), so the outcome is the same on any runner.
     */
    @RepeatedTest(50) void T_16_10_aClientWhoseQueueFillsIsClosed() throws Exception {
        var streams = quiet(BuildSessionStreams.SENDERS);
        var stalled = new Stalled(); var reader = new Reader();
        streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), stalled);
        streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), reader);
        stalled.next(":open", WAIT); reader.next(":open", WAIT);
        streams.heartbeat();
        await(stalled.blocked, "the stalled client's second send blocks");
        reader.next(":heartbeat", WAIT);
        for (int i = 0; i < BuildSessionStreams.QUEUE; i++) {
            streams.heartbeat();
            reader.next(":heartbeat", WAIT);
        }
        assertThat(streams.subscribers()).as("a full queue keeps its stream").isEqualTo(2);
        streams.heartbeat();
        assertThat(streams.subscribers()).as("one frame more closes it").isEqualTo(1);
        reader.next(":heartbeat", WAIT);
        assertThat(stalled.completed).isFalse();
        stalled.release.countDown();
        await(stalled.done, "completed once its blocked send returned");
        assertThat(stalled.frames).as("its queue was dropped: only the blocked send went out").hasSize(1);
        assertThat(stalled.frames.peek()).contains(":heartbeat");
        assertThat(reader.completed).isFalse();
    }

    /**
     * INC-63 point 1 (E11-T02; review of E9-T06 round 2 #1, decision E123): more healthy subscribers than senders is a burst, not
     * a failure. With two senders busy on slow (not stalled) sends, a third stream waits in the bounded queue instead of being
     * refused; once the sends return, all three get `:open` and the next heartbeat, and no stream is closed.
     */
    @Test void T_16_10_INC_63_aBurstAboveTheSendersWaitsAndClosesNoHealthyStream() throws Exception {
        var streams = quiet(2);
        var first = new Stalled(0); var second = new Stalled(0);
        for (var client : List.of(first, second)) {
            streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), client);
            await(client.blocked, "a sender is busy");
        }
        var third = new Reader();
        streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), third);
        assertThat(third.completed).as("SENDERS + 1 healthy streams: the third waits").isFalse();
        assertThat(streams.subscribers()).isEqualTo(3);
        first.release.countDown(); second.release.countDown();
        for (var client : List.of(first, second, third)) { client.next(":open", WAIT); }
        streams.heartbeat();
        for (var client : List.of(first, second, third)) { client.next(":heartbeat", WAIT); }
        assertThat(streams.subscribers()).isEqualTo(3);
        assertThat(first.completed || second.completed || third.completed).as("no stream closed").isFalse();
        assertThat(streams.senderThreads()).isLessThanOrEqualTo(2);
    }

    /**
     * INC-63 point 1: a stream is refused, or a waiting one closed, only when every sender is held by a send blocked for longer
     * than the write timeout (stalled clients whose writes Tomcat has not failed yet).
     */
    @Test void T_16_10_INC_63_aStreamWaitingWhileEverySenderIsStalledIsClosed() throws Exception {
        var streams = quiet(2);
        streams.writeTimeout(Duration.ofMillis(400));
        var first = new Stalled(0); var second = new Stalled(0);
        for (var client : List.of(first, second)) {
            streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), client);
            await(client.blocked, "a sender is held");
        }
        var waiting = new Reader();
        streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), waiting);
        Thread.sleep(500);
        streams.watch();
        assertThat(waiting.completed).as("closed (or refused at once on a slow runner)").isTrue();
        assertThat(waiting.frames).isEmpty();
        assertThat(streams.senderThreads()).isEqualTo(2);
    }

    /**
     * Review #5, round 2: the senders are bounded. With every sender held by a stalled client (a send blocked for longer than
     * the write timeout, INC-63), a new stream is refused at once (closed: EventSource retries later) and no thread is added.
     */
    @Test void T_16_10_theSendersAreBoundedAndAFullPoolRefusesANewStream() throws Exception {
        var streams = quiet(2);
        streams.writeTimeout(Duration.ofMillis(100));
        var first = new Stalled(0); var second = new Stalled(0);
        for (var client : List.of(first, second)) {
            // Its first send (`:open`) already blocks, so each of them holds one sender before the next subscribes.
            streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), client);
            await(client.blocked, "a sender is held");
        }
        Thread.sleep(200);
        var refused = new Reader();
        streams.subscribe(session(0, BuildStatus.NOT_STARTED, ObstacleStatus.NOT_PLACED), refused);
        assertThat(refused.completed).as("refused at once").isTrue();
        assertThat(refused.frames).isEmpty();
        assertThat(streams.senderThreads()).isEqualTo(2);
        assertThat(first.completed || second.completed).as("their blocked sends have not returned").isFalse();
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
        await(reader.done, "the stream ended");
        assertThat(streams.subscribers()).isZero();
        var late = new Reader();
        streams.subscribe(open, late);
        assertThat(late.next("event:finished")).contains("\"status\":\"ABANDONED\"");
        await(late.done, "the late stream ended");
        assertThat(streams.subscribers()).isZero();
    }
}

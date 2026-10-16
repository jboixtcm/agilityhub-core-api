package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.BuildSessionService;
import com.agilityhub.core.courses.domain.BuildSessionRules;
import com.agilityhub.core.courses.domain.CourseParts.BuildObstacle;
import com.agilityhub.core.courses.domain.CourseTypes.BuildStatus;
import com.agilityhub.core.courses.persistence.BuildSession;
import com.agilityhub.core.shared.application.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * S16 R-16-11 live mode: `GET /build-sessions/{id}/events` (`text/event-stream`). Each subscriber keeps the session as it last
 * sent it and receives the difference: one `obstacle` event per changed obstacle (`id` = the session's `version`), then `finished`
 * when the session closes (`COMPLETED` or `ABANDONED`), after which its stream ends. A comment every 20 s keeps proxies and mobile
 * networks from dropping an idle stream. Changes reach a stream right after their commit ({@link BuildSessionService.Changed})
 * and, as a fallback, from a one-second poll of the sessions that have subscribers, so another API instance's write or a missed
 * signal still arrives in under two seconds. Data never names more than the session view does; a client that reconnects reads
 * `GET /build-sessions/{id}` again for the state it missed.
 * <p>
 * E9-T06: the timer only reads and queues. Each subscriber has its own bounded queue ({@link #QUEUE} frames), sent by a pool
 * thread one frame at a time, so a client that stops reading (a full TCP buffer) blocks only its own send: its queue filling up,
 * or one send blocked for {@link #WRITE_TIMEOUT}, closes its stream (EventSource reconnects). A stream lives at most
 * {@link #TIMEOUT} and never longer than the access token it opened with, so a lost role, module or membership stops at the next
 * reconnection; a session that has outlived `courses.buildSessionMaxHours` gets `finished {ABANDONED}` at the next poll.
 * <p>
 * E9-T06 round 2 (known limit): a blocked servlet write cannot be cancelled, so a stalled client keeps its sender thread until
 * Tomcat's write timeout (`server.tomcat.connection-timeout`, 20 s) fails the write. At most {@link #SENDERS} sends run at once:
 * while every sender is held, a stream that needs one is closed instead (a new one is refused at once) and EventSource retries.
 */
@Component
class BuildSessionStreams implements DisposableBean {
    static final Duration HEARTBEAT = Duration.ofSeconds(20);
    static final Duration POLL = Duration.ofSeconds(1);
    /** A stream ends after this long at most; the browser's EventSource reconnects by itself. */
    static final Duration TIMEOUT = Duration.ofMinutes(30);
    /** A send still blocked after this long (a client that stopped reading) closes its stream. */
    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(10);
    /** The frames one subscriber may have waiting; one more closes its stream. */
    static final int QUEUE = 64;
    /** The sends running at once (one per subscriber at most); a stream that finds them all busy is closed. */
    static final int SENDERS = 64;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BuildSessionStreams.class);
    record Key(String clubId, String sessionId) { }

    private final BuildSessionService sessions;
    private final ObjectMapper mapper;
    private final Clock clock;
    /** The polls, heartbeats, after-commit refreshes and the write watchdog; it never writes to a client. */
    private final ScheduledExecutorService timer;
    /** The writes: each subscriber has at most one send running here, so a blocked client holds only its own thread. */
    private final ThreadPoolExecutor senders;
    private final Map<Key, Set<Subscriber>> topics = new ConcurrentHashMap<>();
    private volatile Duration writeTimeout = WRITE_TIMEOUT;

    @org.springframework.beans.factory.annotation.Autowired
    BuildSessionStreams(BuildSessionService sessions, ObjectMapper mapper, Clock clock) { this(sessions, mapper, clock, SENDERS, true); }
    /** Tests: {@code senders} sends at once, and with {@code scheduled} false no heartbeat or poll runs unless called. */
    BuildSessionStreams(BuildSessionService sessions, ObjectMapper mapper, Clock clock, int senders, boolean scheduled) {
        this.sessions = sessions; this.mapper = mapper; this.clock = clock;
        timer = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "build-session-streams"));
        // No queue: a send starts on an idle or new thread up to the cap, else it is refused (and its stream closed).
        this.senders = new ThreadPoolExecutor(0, senders, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> daemon(task, "build-session-sender"));
        if (scheduled) {
            timer.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT.toMillis(), HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
            timer.scheduleWithFixedDelay(this::poll, POLL.toMillis(), POLL.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
    private static Thread daemon(Runnable task, String name) { var thread = new Thread(task, name); thread.setDaemon(true); return thread; }

    /** How long a stream opened at {@code now} with a token expiring at {@code expiresAt} (none: {@link #TIMEOUT}) may live. */
    static Duration lifetime(Instant now, Instant expiresAt) {
        if (expiresAt == null) { return TIMEOUT; }
        var left = Duration.between(now, expiresAt);
        if (left.compareTo(TIMEOUT) > 0) { return TIMEOUT; }
        return left.isNegative() || left.isZero() ? Duration.ofMillis(1) : left;
    }

    /** A new stream on {@code session} as the caller read it, living no longer than the caller's token ({@code expiresAt}). */
    SseEmitter subscribe(BuildSession session, Instant expiresAt) {
        return subscribe(session, new SseEmitter(lifetime(clock.instant(), expiresAt).toMillis()));
    }
    /** {@code emitter} subscribed to {@code session}: a closed or outlived one gets `finished` at once and ends. */
    SseEmitter subscribe(BuildSession session, SseEmitter emitter) {
        var key = new Key(session.clubId(), session.id());
        var subscriber = new Subscriber(key, emitter, session);
        emitter.onCompletion(subscriber::gone); emitter.onError(error -> subscriber.gone()); emitter.onTimeout(subscriber::expired);
        if (!BuildSessionRules.open(session.status())) { subscriber.finish(session); return emitter; }
        if (sessions.over(session)) { subscriber.outlived(session); return emitter; }
        topics.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(subscriber);
        // Commits the response headers now, so the client knows the stream is open before the first change or heartbeat.
        subscriber.comment("open");
        return emitter;
    }

    /** After the commit of a change: refresh that session's streams off the request thread. */
    @TransactionalEventListener(fallbackExecution = true)
    public void changed(BuildSessionService.Changed changed) {
        var key = new Key(changed.clubId(), changed.sessionId());
        if (topics.containsKey(key)) { timer.execute(() -> refresh(key)); }
    }

    int subscribers() { return topics.values().stream().mapToInt(Set::size).sum(); }
    /** The sender threads alive now. */
    int senderThreads() { return senders.getPoolSize(); }
    /** Tests only: a shorter write timeout. */
    void writeTimeout(Duration timeout) { this.writeTimeout = timeout; }

    void poll() {
        for (var key : List.copyOf(topics.keySet())) { refresh(key); }
        long now = System.nanoTime();
        topics.values().forEach(set -> List.copyOf(set).forEach(subscriber -> subscriber.watch(now)));
    }
    void heartbeat() { topics.values().forEach(set -> List.copyOf(set).forEach(subscriber -> subscriber.comment("heartbeat"))); }

    void refresh(Key key) {
        var subscribers = topics.get(key);
        if (subscribers == null || subscribers.isEmpty()) { return; }
        try {
            Optional<BuildSession> current; boolean over;
            try (var scope = TenantContext.open(key.clubId())) {
                current = sessions.current(key.sessionId());
                over = current.isPresent() && sessions.over(current.get());
            }
            for (var subscriber : List.copyOf(subscribers)) {
                if (current.isEmpty()) { subscriber.close(); }
                else if (over) { subscriber.outlived(current.get()); }
                else { subscriber.update(current.get()); }
            }
        } catch (RuntimeException failure) {
            // One session's failure (the database unreachable for a moment) must not stop the timer for every other stream.
            LOG.warn("Build session stream refresh failed sessionId={} clubId={}", key.sessionId(), key.clubId(), failure);
        }
    }
    private void remove(Key key, Subscriber subscriber) {
        topics.computeIfPresent(key, (k, set) -> { set.remove(subscriber); return set.isEmpty() ? null : set; });
    }
    @Override public void destroy() {
        timer.shutdownNow();
        topics.values().forEach(set -> List.copyOf(set).forEach(Subscriber::close));
        topics.clear();
        senders.shutdown();
    }

    /** One stream: what it has sent so far, and its queue of frames, sent one at a time by a pool thread. */
    final class Subscriber {
        private final Key key;
        private final SseEmitter emitter;
        private final Map<String, BuildObstacle> sent = new HashMap<>();
        private final ArrayDeque<SseEmitter.SseEventBuilder> queue = new ArrayDeque<>();
        private long version;
        /** No frame is taken any more: the stream completes once the queue is sent (at once when it was dropped). */
        private boolean closing;
        private boolean draining;
        private boolean completed;
        /** The {@link System#nanoTime()} at which the send in progress started; 0 when none is. */
        private volatile long sending;
        Subscriber(Key key, SseEmitter emitter, BuildSession session) { this.key = key; this.emitter = emitter; remember(session); }

        synchronized void update(BuildSession session) {
            if (closing || session.version() <= version) { return; }
            for (var obstacle : session.obstacles()) {
                if (!obstacle.equals(sent.get(obstacle.obstacleId()))) {
                    var data = new LinkedHashMap<String, Object>();
                    data.put("sessionId", session.id()); data.put("obstacleId", obstacle.obstacleId()); data.put("status", obstacle.status().name());
                    data.put("byAccountId", obstacle.byAccountId()); data.put("at", obstacle.at()); data.put("version", session.version());
                    enqueue(event("obstacle", session.version(), data));
                }
            }
            remember(session);
            if (!BuildSessionRules.open(session.status())) { finish(session); }
        }
        /** The session closed: `finished` with its status and `finishedAt`, then the stream ends. */
        synchronized void finish(BuildSession session) { finished(session, session.status(), session.finishedAt()); }
        /**
         * R-16-11 «> 12 h es tanca sola»: what changed meanwhile, then `finished {ABANDONED}` without `finishedAt` (S15 P9 has not
         * recorded it yet), and the stream ends.
         */
        synchronized void outlived(BuildSession session) {
            update(session);
            finished(session, BuildStatus.ABANDONED, null);
        }
        private void finished(BuildSession session, BuildStatus status, Instant finishedAt) {
            if (closing) { return; }
            var data = new LinkedHashMap<String, Object>();
            data.put("sessionId", session.id()); data.put("status", status.name()); data.put("finishedAt", finishedAt);
            data.put("version", session.version());
            enqueue(event("finished", session.version(), data));
            close();
        }
        synchronized void comment(String text) { if (!closing) { enqueue(SseEmitter.event().comment(text)); } }
        /** Ends the stream once what is queued has been sent. */
        synchronized void close() {
            closing = true;
            remove(key, this);
            if (!draining) { complete(); }
        }
        /** A client that does not read: its queue is dropped and the stream ends as soon as the send in progress returns. */
        synchronized void drop(String reason) {
            if (completed) { return; }
            LOG.info("Build session stream closed ({}) sessionId={} clubId={}", reason, key.sessionId(), key.clubId());
            queue.clear();
            close();
        }
        /** The watchdog: a send blocked for longer than the write timeout drops the stream. */
        void watch(long now) {
            long since = sending;
            if (since != 0 && now - since > writeTimeout.toNanos()) { drop("write timeout"); }
        }
        /** The emitter ended by itself (completion, a network error): nothing more is sent. */
        synchronized void gone() { queue.clear(); closing = true; completed = true; remove(key, this); }
        /** The stream's lifetime is over (30 minutes, or its token's `exp`): a clean end; EventSource reconnects. */
        void expired() {
            synchronized (this) { queue.clear(); closing = true; remove(key, this); if (completed) { return; } completed = true; }
            // Outside the monitor: a send in progress holds the emitter's lock until it returns.
            try { emitter.complete(); } catch (IllegalStateException alreadyDone) { LOG.debug("Build session stream already completed"); }
        }

        private void enqueue(SseEmitter.SseEventBuilder frame) {
            if (closing) { return; }
            if (queue.size() >= QUEUE) { drop("queue full"); return; }
            queue.add(frame);
            if (draining) { return; }
            draining = true;
            try { senders.execute(this::drain); }
            // Every sender is held (stalled clients) or the server stops: the stream ends; EventSource reconnects later.
            catch (RejectedExecutionException busy) { draining = false; drop("no sender"); }
        }
        /** On a pool thread: the queued frames in order, one send at a time, outside the monitor; then the end, when closing. */
        private void drain() {
            while (true) {
                SseEmitter.SseEventBuilder frame;
                synchronized (this) {
                    frame = queue.poll();
                    if (frame == null) { draining = false; if (closing) { complete(); } return; }
                    sending = System.nanoTime();
                }
                try { emitter.send(frame); }
                catch (java.io.IOException | IllegalStateException gone) { synchronized (this) { queue.clear(); closing = true; remove(key, this); } }
                finally { sending = 0; }
            }
        }
        /** Under the monitor, with no send in progress. */
        private void complete() {
            if (completed) { return; }
            completed = true;
            try { emitter.complete(); } catch (IllegalStateException alreadyDone) { LOG.debug("Build session stream already completed"); }
        }
        private SseEmitter.SseEventBuilder event(String name, long id, Map<String, Object> data) {
            try { return SseEmitter.event().name(name).id(String.valueOf(id)).data(mapper.writeValueAsString(data)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
        }
        private void remember(BuildSession session) {
            version = session.version();
            session.obstacles().forEach(obstacle -> sent.put(obstacle.obstacleId(), obstacle));
        }
    }
}

package com.agilityhub.core.courses.api;

import com.agilityhub.core.courses.application.BuildSessionService;
import com.agilityhub.core.courses.domain.BuildSessionRules;
import com.agilityhub.core.courses.domain.CourseParts.BuildObstacle;
import com.agilityhub.core.courses.persistence.BuildSession;
import com.agilityhub.core.shared.application.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
 */
@Component
class BuildSessionStreams implements DisposableBean {
    static final Duration HEARTBEAT = Duration.ofSeconds(20);
    static final Duration POLL = Duration.ofSeconds(1);
    /** A stream ends after this long; the browser's EventSource reconnects by itself. */
    static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BuildSessionStreams.class);
    record Key(String clubId, String sessionId) { }

    private final BuildSessionService sessions;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService timer;
    private final Map<Key, Set<Subscriber>> topics = new ConcurrentHashMap<>();

    BuildSessionStreams(BuildSessionService sessions, ObjectMapper mapper) {
        this.sessions = sessions; this.mapper = mapper;
        timer = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "build-session-streams"); thread.setDaemon(true); return thread;
        });
        timer.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT.toMillis(), HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::poll, POLL.toMillis(), POLL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** A new stream on {@code session} as the caller read it: a closed one gets `finished` at once and ends. */
    SseEmitter subscribe(BuildSession session) {
        var emitter = new SseEmitter(TIMEOUT.toMillis());
        var subscriber = new Subscriber(emitter, session);
        if (!BuildSessionRules.open(session.status())) { subscriber.finish(session); return emitter; }
        var key = new Key(session.clubId(), session.id());
        topics.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(subscriber);
        Runnable remove = () -> remove(key, subscriber);
        emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(error -> remove.run());
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

    private void poll() { for (var key : List.copyOf(topics.keySet())) { refresh(key); } }
    private void heartbeat() { topics.values().forEach(set -> set.forEach(subscriber -> subscriber.comment("heartbeat"))); }

    void refresh(Key key) {
        var subscribers = topics.get(key);
        if (subscribers == null || subscribers.isEmpty()) { return; }
        try {
            Optional<BuildSession> current;
            try (var scope = TenantContext.open(key.clubId())) { current = sessions.current(key.sessionId()); }
            for (var subscriber : List.copyOf(subscribers)) {
                if (current.isEmpty()) { subscriber.end(); } else { subscriber.update(current.get()); }
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
        topics.values().forEach(set -> set.forEach(Subscriber::end));
        topics.clear();
    }

    /** One stream: what it has sent so far, and its sends, one at a time. */
    final class Subscriber {
        private final SseEmitter emitter;
        private final Map<String, BuildObstacle> sent = new HashMap<>();
        private long version;
        private boolean ended;
        Subscriber(SseEmitter emitter, BuildSession session) { this.emitter = emitter; remember(session); }

        synchronized void update(BuildSession session) {
            if (ended || session.version() <= version) { return; }
            for (var obstacle : session.obstacles()) {
                if (!obstacle.equals(sent.get(obstacle.obstacleId()))) {
                    var data = new LinkedHashMap<String, Object>();
                    data.put("sessionId", session.id()); data.put("obstacleId", obstacle.obstacleId()); data.put("status", obstacle.status().name());
                    data.put("byAccountId", obstacle.byAccountId()); data.put("at", obstacle.at()); data.put("version", session.version());
                    event("obstacle", session.version(), data);
                }
            }
            remember(session);
            if (!BuildSessionRules.open(session.status())) { finish(session); }
        }
        synchronized void finish(BuildSession session) {
            var data = new LinkedHashMap<String, Object>();
            data.put("sessionId", session.id()); data.put("status", session.status().name()); data.put("finishedAt", session.finishedAt());
            data.put("version", session.version());
            event("finished", session.version(), data);
            end();
        }
        synchronized void comment(String text) {
            if (ended) { return; }
            try { emitter.send(SseEmitter.event().comment(text)); } catch (java.io.IOException | IllegalStateException gone) { end(); }
        }
        synchronized void end() {
            if (ended) { return; }
            ended = true;
            try { emitter.complete(); } catch (IllegalStateException alreadyDone) { LOG.debug("Build session stream already completed"); }
        }
        private void event(String name, long id, Map<String, Object> data) {
            if (ended) { return; }
            try { emitter.send(SseEmitter.event().name(name).id(String.valueOf(id)).data(mapper.writeValueAsString(data))); }
            catch (java.io.IOException | IllegalStateException gone) { end(); }
        }
        private void remember(BuildSession session) {
            version = session.version();
            session.obstacles().forEach(obstacle -> sent.put(obstacle.obstacleId(), obstacle));
        }
    }
}

package com.agilityhub.core.shared.persistence;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * INC-01 semantics (E5-T27, audit A7-07): the health's `UP` means the database answers. A Mongo `ping` bounded to 1 s, run on at
 * most two daemon threads outside any request transaction, so a database that hangs never holds a request longer than the bound
 * and never piles up threads (a third probe while two still wait answers DOWN at once). It reads no club, account or data.
 */
@Component
public class DatabaseProbe implements DisposableBean {
    public static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseProbe.class);
    private final MongoTemplate mongo;
    private final ThreadPoolExecutor pings = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
        var thread = new Thread(runnable, "database-probe"); thread.setDaemon(true); return thread;
    });

    public DatabaseProbe(MongoTemplate mongo) { this.mongo = mongo; }

    /** True when the database answered a `ping` within {@link #TIMEOUT}. */
    public boolean up() {
        java.util.concurrent.Future<Document> ping;
        try { ping = pings.submit(() -> mongo.getDb().runCommand(new Document("ping", 1))); }
        catch (RejectedExecutionException busy) { LOG.debug("Database probe busy: two pings still wait"); return false; }
        try {
            ping.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException slow) {
            ping.cancel(true);
            return false;
        } catch (ExecutionException failed) {
            LOG.debug("Database ping failed: {}", failed.getCause().getClass().getName());
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override public void destroy() { pings.shutdownNow(); }
}

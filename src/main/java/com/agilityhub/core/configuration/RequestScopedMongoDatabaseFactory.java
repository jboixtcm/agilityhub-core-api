package com.agilityhub.core.configuration;

import com.mongodb.ClientSessionOptions;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.data.mongodb.MongoDatabaseFactory;

/** E85: bind both ordinary operations and transaction sessions to the current servlet execution's budget. */
final class RequestScopedMongoDatabaseFactory implements MongoDatabaseFactory {
    private final MongoDatabaseFactory delegate;
    private final Supplier<Long> requestTimeout;
    private final boolean sessionBound;

    RequestScopedMongoDatabaseFactory(MongoDatabaseFactory delegate, Supplier<Long> requestTimeout) {
        this(delegate, requestTimeout, false);
    }

    private RequestScopedMongoDatabaseFactory(MongoDatabaseFactory delegate, Supplier<Long> requestTimeout, boolean sessionBound) {
        this.delegate = delegate;
        this.requestTimeout = requestTimeout;
        this.sessionBound = sessionBound;
    }

    private MongoDatabase bounded(MongoDatabase database) {
        Long timeout = requestTimeout.get();
        // A session owns its deadline, including after-commit reads; a database override conflicts with that context.
        return timeout == null || sessionBound ? database : database.withTimeout(timeout, TimeUnit.MILLISECONDS);
    }

    @Override public MongoDatabase getMongoDatabase() { return bounded(delegate.getMongoDatabase()); }
    @Override public MongoDatabase getMongoDatabase(String name) { return bounded(delegate.getMongoDatabase(name)); }
    @Override public PersistenceExceptionTranslator getExceptionTranslator() { return delegate.getExceptionTranslator(); }
    @Override public boolean isTransactionActive() { return delegate.isTransactionActive(); }

    @Override public ClientSession getSession(ClientSessionOptions options) {
        Long timeout = requestTimeout.get();
        return delegate.getSession(timeout == null ? options
                : ClientSessionOptions.builder(options).defaultTimeout(timeout, TimeUnit.MILLISECONDS).build());
    }

    @Override public MongoDatabaseFactory withSession(ClientSession session) {
        return new RequestScopedMongoDatabaseFactory(delegate.withSession(session), requestTimeout, true);
    }
}

package com.agilityhub.core.shared.application;

import com.mongodb.MongoException;
import org.springframework.data.mongodb.core.index.IndexOperations;

/** A startup index replacement may race the same replacement on another API instance. */
public final class IndexDrops {
    private IndexDrops() { }

    /** Ignore only Mongo's IndexNotFound (27); permission, connection and other errors still fail startup. */
    public static void ifPresent(IndexOperations indexes, String name) {
        try { indexes.dropIndex(name); }
        catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof MongoException mongo && mongo.getCode() == 27) { return; }
            }
            throw failure;
        }
    }
}

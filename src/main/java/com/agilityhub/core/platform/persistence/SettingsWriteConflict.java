package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.mongodb.MongoException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

/** Concurrent settings editors receive the same contract for CAS and Mongo transaction conflicts. */
public final class SettingsWriteConflict {
    private SettingsWriteConflict() { }
    public static RuntimeException translate(DataAccessException failure) {
        if (failure instanceof DuplicateKeyException || failure instanceof OptimisticLockingFailureException) {
            return new ApiException(ErrorCode.STALE_VERSION);
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongo && mongo.getCode() == 112) { return new ApiException(ErrorCode.STALE_VERSION); }
        }
        return failure;
    }
}

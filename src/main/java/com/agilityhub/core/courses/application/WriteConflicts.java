package com.agilityhub.core.courses.application;

import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.function.Supplier;

/**
 * E115 (E9-T02 review #7): S16's writes that race on one document — a placement or setup that references a ring's geometry or a
 * course against the change or removal of that geometry or course — meet as a Mongo write conflict. The transaction that loses
 * answers `409 STALE_VERSION` (the client reads again), never a `500`; anything else is rethrown.
 */
final class WriteConflicts {
    private WriteConflicts() { }

    static void guard(Runnable write) { guard(() -> { write.run(); return null; }); }

    static <T> T guard(Supplier<T> write) {
        try {
            return write.get();
        } catch (RuntimeException failure) {
            if (!(failure instanceof ApiException) && TransactionRetries.conflict(failure)) { throw new ApiException(ErrorCode.STALE_VERSION); }
            throw failure;
        }
    }
}

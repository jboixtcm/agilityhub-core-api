package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.training.domain.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** S09 R-09-06 retry policy, the §8 notification routing (N-06 / N-07 / N-47) and the S06 reason mapping, without Spring. */
class TrainingApplicationTest {
    /** Runs the callback directly, as one "transaction" per attempt. */
    static final class DirectTransactions extends TransactionTemplate {
        final AtomicInteger attempts = new AtomicInteger();
        @Override public <T> T execute(TransactionCallback<T> action) { attempts.incrementAndGet(); return action.doInTransaction((TransactionStatus) null); }
    }
    static MongoCommandException mongo(int code, String label) {
        var response = new BsonDocument("ok", new BsonInt32(0)).append("code", new BsonInt32(code)).append("errmsg", new BsonString("fictional"));
        if (label != null) { response.append("errorLabels", new org.bson.BsonArray(List.of(new BsonString(label)))); }
        return new MongoCommandException(response, new ServerAddress());
    }

    @Test void T_09_32_duplicateKeysAndWriteConflictsAreRetriedWholeAtMostThreeTimes() {
        assertThat(TrainingTransactions.retryable(new org.springframework.dao.DuplicateKeyException("seat"))).isTrue();
        assertThat(TrainingTransactions.retryable(new IllegalStateException(mongo(112, null)))).as("a wrapped write conflict").isTrue();
        assertThat(TrainingTransactions.retryable(mongo(11000, null))).isTrue();
        assertThat(TrainingTransactions.retryable(mongo(251, "TransientTransactionError"))).isTrue();
        assertThat(TrainingTransactions.retryable(mongo(2, null))).isFalse();
        assertThat(TrainingTransactions.retryable(new ApiException(ErrorCode.SLOT_TAKEN))).isFalse();
        try (var tenant = TenantContext.open("club-a")) {
            var direct = new DirectTransactions(); var transactions = new TrainingTransactions(direct);
            var calls = new AtomicInteger();
            assertThat(transactions.write(List.of("dog:a", "slot:x"), () -> {
                if (calls.incrementAndGet() < 3) { throw new org.springframework.dao.DuplicateKeyException("seat"); }
                return "booked";
            })).isEqualTo("booked");
            assertThat(direct.attempts.get()).isEqualTo(3);
            var exhausted = new DirectTransactions();
            assertThatThrownBy(() -> new TrainingTransactions(exhausted).write(List.of("dog:a"), () -> { throw mongo(112, null); }))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));
            assertThat(exhausted.attempts.get()).isEqualTo(3);
            var business = new DirectTransactions();
            assertThatThrownBy(() -> new TrainingTransactions(business).write(Arrays.asList("dog:a", null), () -> { throw new ApiException(ErrorCode.SLOT_TAKEN); }))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SLOT_TAKEN));
            assertThat(business.attempts.get()).as("a business refusal is never retried").isEqualTo(1);
        }
    }

    static TrainingEvent event(TrainingEvent.Kind kind, String origin, String by, String reason) {
        var payload = new LinkedHashMap<String, Object>(); payload.put("origin", origin);
        if (by != null) { payload.put("by", by); } if (reason != null) { payload.put("cancelReason", reason); }
        return new TrainingEvent(kind, "club-a", "tb-1", Instant.EPOCH, payload, null, null, DomainEvent.Origin.APP);
    }
    @Test void T_09_16_T_09_21_eachEventMapsToTheRightCatalogNotification() {
        var booked = event(TrainingEvent.Kind.TrainingBooked, "APP", null, null);
        assertThat(TrainingNotifications.code("N-06", booked)).contains("N-06");
        assertThat(TrainingNotifications.code("N-47", booked)).isEmpty();
        assertThat(TrainingNotifications.code("N-07", booked)).isEmpty();
        var byClub = event(TrainingEvent.Kind.TrainingBooked, "BACKOFFICE", null, null);
        assertThat(TrainingNotifications.code("N-06", byClub)).contains("N-06"); assertThat(TrainingNotifications.code("N-47", byClub)).contains("N-47");
        var member = event(TrainingEvent.Kind.TrainingCancelled, "APP", "MEMBER", "MEMBER_REQUEST");
        assertThat(TrainingNotifications.code("N-07", member)).contains("N-07"); assertThat(TrainingNotifications.code("N-47", member)).isEmpty();
        assertThat(TrainingNotifications.code("N-06", member)).isEmpty();
        var ringBlock = event(TrainingEvent.Kind.TrainingCancelled, "BACKOFFICE", "ADMIN", "RING_BLOCK");
        assertThat(TrainingNotifications.code("N-47", ringBlock)).contains("N-47"); assertThat(TrainingNotifications.code("N-07", ringBlock)).as("N-47 replaces N-07").isEmpty();
        var impersonated = event(TrainingEvent.Kind.TrainingCancelled, "BACKOFFICE", "MEMBER", "MEMBER_REQUEST");
        assertThat(TrainingNotifications.code("N-47", impersonated)).contains("N-47"); assertThat(TrainingNotifications.code("N-07", impersonated)).isEmpty();
        var left = event(TrainingEvent.Kind.TrainingCancelled, "SYSTEM", "SYSTEM", "MEMBER_LEFT");
        assertThat(TrainingNotifications.code("N-07", left)).isEmpty(); assertThat(TrainingNotifications.code("N-47", left)).isEmpty();
        var inactivity = event(TrainingEvent.Kind.TrainingCancelled, "SYSTEM", "SYSTEM", "INACTIVITY");
        assertThat(TrainingNotifications.code("N-07", inactivity)).contains("N-07");
        assertThat(TrainingNotifications.code("N-99", inactivity)).isEmpty();
        assertThat(TrainingNotifications.code("N-07", new TrainingEvent(TrainingEvent.Kind.TrainingCancelled, "club-a", "tb", Instant.EPOCH, Map.of(), null, null, null)))
                .as("a payload without origin/by is a member cancellation").contains("N-07");
    }

    @Test void T_09_28_theCallerReasonMapsToTheCatalogCancelReason() {
        assertThat(TrainingConflictService.reason(null)).isEqualTo(TrainingCancelReason.RING_BLOCK);
        assertThat(TrainingConflictService.reason("RING_BLOCK")).isEqualTo(TrainingCancelReason.RING_BLOCK);
        assertThat(TrainingConflictService.reason("CLASS_SESSION")).isEqualTo(TrainingCancelReason.CLASS_CONFLICT);
        assertThat(TrainingConflictService.reason("CLASS_CONFLICT")).isEqualTo(TrainingCancelReason.CLASS_CONFLICT);
        assertThat(TrainingConflictService.reason("RING_NOT_RESERVABLE")).isEqualTo(TrainingCancelReason.RING_NOT_RESERVABLE);
    }

    @Test void theSystemActorHasNoAccountAndAnImpersonationKeepsTheAdmin() {
        var system = TrainingActor.system();
        assertThat(system.impersonated()).isFalse(); assertThat(TrainingEvents.origin(system)).isEqualTo(DomainEvent.Origin.SYSTEM);
        assertThat(TrainingEvents.origin(system.as(TrainingCancelledBy.ADMIN))).isEqualTo(DomainEvent.Origin.BACKOFFICE);
        var actor = new TrainingActor("admin", "member", "Admin", "member", TrainingOrigin.BACKOFFICE, TrainingCancelledBy.MEMBER);
        assertThat(actor.impersonated()).isTrue(); assertThat(actor.as(TrainingCancelledBy.ADMIN).by()).isEqualTo(TrainingCancelledBy.ADMIN);
        assertThat(TrainingEvents.origin(new TrainingActor("a", "m", "M", null, TrainingOrigin.APP, TrainingCancelledBy.MEMBER))).isEqualTo(DomainEvent.Origin.APP);
        assertThat(TrainingActor.member("jwt-member").memberId()).as("no current user outside a request").isEqualTo("jwt-member");
        assertThat(TrainingActor.club().by()).isEqualTo(TrainingCancelledBy.ADMIN);
    }
}

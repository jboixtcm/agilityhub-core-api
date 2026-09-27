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

    static TrainingTransactions transactions(org.springframework.transaction.support.TransactionTemplate template) {
        return new TrainingTransactions(template, new com.agilityhub.core.shared.application.LocalLanes(false),
                new com.agilityhub.core.shared.application.TransactionRetries(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }
    @Test void T_09_32_duplicateKeysAndWriteConflictsAreRetriedWholeAtMostThreeTimes() {
        assertThat(TrainingTransactions.retryable(new org.springframework.dao.DuplicateKeyException("seat"))).isTrue();
        assertThat(TrainingTransactions.retryable(new IllegalStateException(mongo(112, null)))).as("a wrapped write conflict").isTrue();
        assertThat(TrainingTransactions.retryable(mongo(11000, null))).isTrue();
        assertThat(TrainingTransactions.retryable(mongo(251, "TransientTransactionError"))).isTrue();
        assertThat(TrainingTransactions.retryable(mongo(2, null))).isFalse();
        assertThat(TrainingTransactions.retryable(new ApiException(ErrorCode.SLOT_TAKEN))).isFalse();
        try (var tenant = TenantContext.open("club-a")) {
            var direct = new DirectTransactions(); var transactions = transactions(direct);
            var calls = new AtomicInteger();
            assertThat(transactions.write(List.of("dog:a", "slot:x"), () -> {
                if (calls.incrementAndGet() < 3) { throw new org.springframework.dao.DuplicateKeyException("seat"); }
                return "booked";
            })).isEqualTo("booked");
            assertThat(direct.attempts.get()).isEqualTo(3);
            var exhausted = new DirectTransactions();
            assertThatThrownBy(() -> transactions(exhausted).write(List.of("dog:a"), () -> { throw mongo(112, null); }))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.STALE_VERSION));
            assertThat(exhausted.attempts.get()).isEqualTo(3);
            var business = new DirectTransactions();
            assertThatThrownBy(() -> transactions(business).write(Arrays.asList("dog:a", null), () -> { throw new ApiException(ErrorCode.SLOT_TAKEN); }))
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
        assertThat(TrainingNotificationFacts.applies("N-06", trigger(booked))).isTrue();
        assertThat(TrainingNotificationFacts.applies("N-47", trigger(booked))).isFalse();
        assertThat(TrainingNotificationFacts.applies("N-07", trigger(booked))).isFalse();
        var byClub = event(TrainingEvent.Kind.TrainingBooked, "BACKOFFICE", null, null);
        assertThat(TrainingNotificationFacts.applies("N-06", trigger(byClub))).isTrue(); assertThat(TrainingNotificationFacts.applies("N-47", trigger(byClub))).isTrue();
        var member = event(TrainingEvent.Kind.TrainingCancelled, "APP", "MEMBER", "MEMBER_REQUEST");
        assertThat(TrainingNotificationFacts.applies("N-07", trigger(member))).isTrue(); assertThat(TrainingNotificationFacts.applies("N-47", trigger(member))).isFalse();
        assertThat(TrainingNotificationFacts.applies("N-06", trigger(member))).isFalse();
        var ringBlock = event(TrainingEvent.Kind.TrainingCancelled, "BACKOFFICE", "ADMIN", "RING_BLOCK");
        assertThat(TrainingNotificationFacts.applies("N-47", trigger(ringBlock))).isTrue(); assertThat(TrainingNotificationFacts.applies("N-07", trigger(ringBlock))).as("N-47 replaces N-07").isFalse();
        var impersonated = event(TrainingEvent.Kind.TrainingCancelled, "BACKOFFICE", "MEMBER", "MEMBER_REQUEST");
        assertThat(TrainingNotificationFacts.applies("N-47", trigger(impersonated))).isTrue(); assertThat(TrainingNotificationFacts.applies("N-07", trigger(impersonated))).isFalse();
        var left = event(TrainingEvent.Kind.TrainingCancelled, "SYSTEM", "SYSTEM", "MEMBER_LEFT");
        assertThat(TrainingNotificationFacts.applies("N-07", trigger(left))).isFalse(); assertThat(TrainingNotificationFacts.applies("N-47", trigger(left))).isFalse();
        var inactivity = event(TrainingEvent.Kind.TrainingCancelled, "SYSTEM", "SYSTEM", "INACTIVITY");
        assertThat(TrainingNotificationFacts.applies("N-07", trigger(inactivity))).isTrue();
        assertThat(TrainingNotificationFacts.applies("N-99", trigger(inactivity))).isFalse();
        assertThat(TrainingNotificationFacts.applies("N-07", trigger(new TrainingEvent(TrainingEvent.Kind.TrainingCancelled, "club-a", "tb", Instant.EPOCH, Map.of(), null, null, null))))
                .as("a payload without origin/by is a member cancellation").isTrue();
    }
    /** The event as the S11 engine reads it from the outbox (E7-T02). */
    private static com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger trigger(TrainingEvent e) {
        return new com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger("event-1", e.type(), e.clubId(), e.aggregateType(), e.aggregateId(),
                e.occurredAt(), e.payload(), e.actorAccountId(), e.impersonatedMemberId(), e.origin());
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

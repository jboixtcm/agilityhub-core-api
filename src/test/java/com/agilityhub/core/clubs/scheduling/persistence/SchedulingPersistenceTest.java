package com.agilityhub.core.clubs.scheduling.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** E5-T15 (review E5-T07 #3): `ring_slot_locks` is created by the `schedulingCollections` runner, never by the repository. */
class SchedulingPersistenceTest {
    private static void run(MongoTemplate mongo) throws Exception {
        new SchedulingPersistence().schedulingCollections(mongo).run(new DefaultApplicationArguments());
    }
    private static MongoTemplate mongo(boolean exists, IndexOperations indexes) {
        var mongo = mock(MongoTemplate.class);
        when(mongo.collectionExists(RingSlotLock.class)).thenReturn(exists);
        when(mongo.indexOps(RingSlotLock.class)).thenReturn(indexes);
        return mongo;
    }
    private static IndexInfo index(String name, Duration expireAfter) {
        var info = mock(IndexInfo.class);
        when(info.getName()).thenReturn(name); when(info.getExpireAfter()).thenReturn(Optional.ofNullable(expireAfter));
        return info;
    }

    @Test void E5_T15_theRepositoryConstructorTouchesNoDatabase() {
        var mongo = mock(MongoTemplate.class);
        new RingSlotLockRepository(mongo);
        verifyNoInteractions(mongo);
    }

    @Test void E5_T15_theStartupRunnerCreatesTheRingSlotCollectionOnceAndToleratesARace() throws Exception {
        var missing = mongo(false, mock(IndexOperations.class));
        run(missing);
        verify(missing).createCollection(RingSlotLock.class);

        var present = mongo(true, mock(IndexOperations.class));
        run(present);
        verify(present, never()).createCollection(RingSlotLock.class);

        // Another instance created it between the check and the create: not a failure.
        var raced = mongo(false, mock(IndexOperations.class));
        when(raced.collectionExists(RingSlotLock.class)).thenReturn(false, true);
        when(raced.createCollection(RingSlotLock.class)).thenThrow(new DataAccessResourceFailureException("namespace already exists"));
        assertThatCode(() -> run(raced)).doesNotThrowAnyException();

        var broken = mongo(false, mock(IndexOperations.class));
        when(broken.createCollection(RingSlotLock.class)).thenThrow(new DataAccessResourceFailureException("unreachable"));
        assertThatThrownBy(() -> run(broken)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    /**
     * E5-T17 (S15 R-15-19 amended 25-09): the runner ensures the `expiresAt` TTL index (`expireAfterSeconds: 0`) on every
     * start, also when the collection already exists; an identical index built meanwhile by another instance is no failure,
     * a conflicting one is.
     */
    @Test void E5_T17_theStartupRunnerEnsuresTheRingSlotTtlIndexOnEveryStart() throws Exception {
        for (boolean exists : List.of(false, true)) {
            var indexes = mock(IndexOperations.class);
            run(mongo(exists, indexes));
            verify(indexes).ensureIndex(argThat((IndexDefinition definition) -> {
                var options = definition.getIndexOptions();
                return definition.getIndexKeys().equals(new org.bson.Document("expiresAt", 1)) && SchedulingPersistence.RING_SLOT_TTL.equals(options.getString("name"))
                        && options.get("expireAfterSeconds") instanceof Number seconds && seconds.longValue() == 0;
            }));
        }
        var raced = mock(IndexOperations.class);
        when(raced.ensureIndex(any(Index.class))).thenThrow(new DataAccessResourceFailureException("index build already in progress"));
        var built = List.of(index("_id_", null), index(SchedulingPersistence.RING_SLOT_TTL, Duration.ZERO));
        when(raced.getIndexInfo()).thenReturn(built);
        assertThatCode(() -> run(mongo(true, raced))).doesNotThrowAnyException();

        var conflicting = mock(IndexOperations.class);
        when(conflicting.ensureIndex(any(Index.class))).thenThrow(new DataAccessResourceFailureException("IndexOptionsConflict"));
        var other = List.of(index(SchedulingPersistence.RING_SLOT_TTL, Duration.ofDays(1)));
        when(conflicting.getIndexInfo()).thenReturn(other);
        assertThatThrownBy(() -> run(mongo(true, conflicting))).isInstanceOf(DataAccessResourceFailureException.class);
    }
}

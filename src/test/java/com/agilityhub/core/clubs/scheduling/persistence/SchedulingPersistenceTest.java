package com.agilityhub.core.clubs.scheduling.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** E5-T15 (review E5-T07 #3): `ring_slot_locks` is created by the `schedulingCollections` runner, never by the repository. */
class SchedulingPersistenceTest {
    private static void run(MongoTemplate mongo) throws Exception {
        new SchedulingPersistence().schedulingCollections(mongo).run(new DefaultApplicationArguments());
    }

    @Test void E5_T15_theRepositoryConstructorTouchesNoDatabase() {
        var mongo = mock(MongoTemplate.class);
        new RingSlotLockRepository(mongo);
        verifyNoInteractions(mongo);
    }

    @Test void E5_T15_theStartupRunnerCreatesTheRingSlotCollectionOnceAndToleratesARace() throws Exception {
        var missing = mock(MongoTemplate.class);
        when(missing.collectionExists(RingSlotLock.class)).thenReturn(false);
        run(missing);
        verify(missing).createCollection(RingSlotLock.class);

        var present = mock(MongoTemplate.class);
        when(present.collectionExists(RingSlotLock.class)).thenReturn(true);
        run(present);
        verify(present, never()).createCollection(RingSlotLock.class);

        // Another instance created it between the check and the create: not a failure.
        var raced = mock(MongoTemplate.class);
        when(raced.collectionExists(RingSlotLock.class)).thenReturn(false, true);
        when(raced.createCollection(RingSlotLock.class)).thenThrow(new DataAccessResourceFailureException("namespace already exists"));
        assertThatCode(() -> run(raced)).doesNotThrowAnyException();

        var broken = mock(MongoTemplate.class);
        when(broken.collectionExists(RingSlotLock.class)).thenReturn(false);
        when(broken.createCollection(RingSlotLock.class)).thenThrow(new DataAccessResourceFailureException("unreachable"));
        assertThatThrownBy(() -> run(broken)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}

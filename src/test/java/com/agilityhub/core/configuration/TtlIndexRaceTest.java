package com.agilityhub.core.configuration;

import com.agilityhub.core.identity.persistence.ImpersonationGrant;
import com.agilityhub.core.identity.persistence.ImpersonationGrantRepository;
import com.agilityhub.core.platform.persistence.SecurityEvent;
import com.agilityhub.core.platform.persistence.SecurityEventRepository;
import com.mongodb.MongoException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TtlIndexRaceTest {
    @Test void R_14_16_twoInstancesMayDropTheSameLegacyRetentionIndex() {
        for (boolean security : List.of(false, true)) {
            var indexes = mock(IndexOperations.class);
            var mongo = mock(MongoTemplate.class);
            var old = mock(IndexInfo.class);
            when(old.getName()).thenReturn("legacy_retention");
            when(old.getIndexFields()).thenReturn(List.of(IndexField.create(security ? "at" : "expiresAt", Sort.Direction.ASC)));
            when(old.getExpireAfter()).thenReturn(Optional.of(Duration.ZERO));
            when(indexes.getIndexInfo()).thenReturn(List.of(old));
            if (security) { when(mongo.indexOps(SecurityEvent.class)).thenReturn(indexes); }
            else { when(mongo.indexOps(ImpersonationGrant.class)).thenReturn(indexes); }
            Runnable start = security ? () -> new SecurityEventRepository(mongo).ensureIndexes(90)
                    : () -> new ImpersonationGrantRepository(mongo).ensureIndexes();
            doThrow(new DataAccessResourceFailureException("Another instance removed the old index", new MongoException(27, "IndexNotFound")))
                    .when(indexes).dropIndex("legacy_retention");
            assertThatCode(start::run).doesNotThrowAnyException();
            verify(indexes).createIndex(argThat((Index index) -> index.getIndexKeys()
                    .equals(new org.bson.Document(security ? "at" : "expiresAt", 1))));
            reset(indexes);
            when(indexes.getIndexInfo()).thenReturn(List.of(old));
            var denied = new DataAccessResourceFailureException("Not authorized", new MongoException(13, "Unauthorized"));
            doThrow(denied).when(indexes).dropIndex("legacy_retention");
            assertThatThrownBy(start::run).isSameAs(denied);
            verify(indexes, never()).createIndex(any(Index.class));
        }
    }
}

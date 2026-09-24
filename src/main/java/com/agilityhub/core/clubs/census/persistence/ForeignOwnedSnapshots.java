package com.agilityhub.core.clubs.census.persistence;

import org.bson.Document;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertCallback;

/**
 * Records the {@link ForeignOwned} values of every census entity read from Mongo, so that {@link CensusRepository#save}
 * can tell a field changed in memory (a lost write: fail fast) from one changed in Mongo by another vertical since the
 * read (kept, never a conflict) (E5-T11).
 */
public class ForeignOwnedSnapshots implements AfterConvertCallback<CensusEntity> {
    @Override
    public CensusEntity onAfterConvert(CensusEntity entity, Document document, String collection) {
        if (!CensusRepository.foreignFields(entity.getClass()).isEmpty()) { entity.loadedForeign = CensusRepository.foreignValues(entity); }
        return entity;
    }
}

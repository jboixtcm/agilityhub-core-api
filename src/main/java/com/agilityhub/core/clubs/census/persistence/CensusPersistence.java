package com.agilityhub.core.clubs.census.persistence;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.MongoTemplate;

@Configuration
public class CensusPersistence {
    @Bean public ForeignOwnedSnapshots foreignOwnedSnapshots() { return new ForeignOwnedSnapshots(); }
    @Bean public CensusRepository<Member> members(MongoTemplate mongo, Clock clock) {
        var repo = new CensusRepository<>(mongo, Member.class, clock);
        repo.ensureIndexes("memberNumber", "number", "member_number"); repo.ensureIndexes("idDocument.number", "string", "member_id_document");
        repo.ensureLookup("accountId"); repo.ensureIndexes("externalIds.playoff", "array", "member_playoff_ids");
        // R-04-05 (E3-T09): the anonymous identity check finds a primary email through this index, never by a scan; a pending
        // readmission is also found by the address it submitted (R-04-06 a, round 2).
        repo.ensureLookup("contactEmails.email"); repo.ensureLookup("readmissionRequest.submitted.contactEmails.email"); return repo;
    }
    @Bean public CensusRepository<Dog> dogs(MongoTemplate mongo, Clock clock) {
        var repo = new CensusRepository<>(mongo, Dog.class, clock); repo.ensureIndexes("chip", "string", "dog_chip");
        repo.ensureLookup("memberId"); repo.ensureLookup("levelId"); repo.ensureIndexes("sourceIds.playoffMemberId", "string", "dog_playoff_id");
        // R-04-12 (E3-T09): the anonymous family lookup starts from the dog's normalised name key, case- and accent-insensitive.
        repo.ensureNameIndex(); return repo;
    }
    /** R-04-12 (E3-T09 round 2): the idempotent `Dog.nameKey` migration of the dogs stored before the key, at every start. */
    @Bean public org.springframework.boot.ApplicationRunner dogNameKeyMigration(CensusRepository<Dog> dogs) {
        return arguments -> {
            long migrated = dogs.backfillNameKeys();
            if (migrated > 0) { org.slf4j.LoggerFactory.getLogger(CensusPersistence.class).info("Dog.nameKey migrated for {} dogs", migrated); }
        };
    }
    @Bean public CensusRepository<FamilyGroup> familyGroups(MongoTemplate mongo, Clock clock) {
        var repo = new CensusRepository<>(mongo, FamilyGroup.class, clock); repo.ensureLookup("memberIds"); repo.ensureIndexes("sourceIds.playoffGroupId", "string", "group_playoff_id"); return repo;
    }
    @Bean public CensusRepository<DogDocument> dogDocuments(MongoTemplate mongo, Clock clock) {
        var repo = new CensusRepository<>(mongo, DogDocument.class, clock); repo.documentIndex(); return repo;
    }
}

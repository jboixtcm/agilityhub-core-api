package com.agilityhub.core.clubs.census.persistence;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.MongoTemplate;

@Configuration
public class CensusPersistence {
    @Bean public CensusRepository<Member> members(MongoTemplate mongo, Clock clock) {
        var repo = new CensusRepository<>(mongo, Member.class, clock);
        repo.ensureIndexes("memberNumber", "number", "member_number"); repo.ensureIndexes("idDocument.number", "string", "member_id_document");
        repo.ensureLookup("accountId"); return repo;
    }
    @Bean public CensusRepository<Dog> dogs(MongoTemplate mongo, Clock clock) {
        var repo = new CensusRepository<>(mongo, Dog.class, clock); repo.ensureIndexes("chip", "string", "dog_chip");
        repo.ensureLookup("memberId"); repo.ensureLookup("levelId"); return repo;
    }
    @Bean public CensusRepository<FamilyGroup> familyGroups(MongoTemplate mongo, Clock clock) {
        var repo = new CensusRepository<>(mongo, FamilyGroup.class, clock); repo.ensureLookup("memberIds"); return repo;
    }
    @Bean public CensusRepository<DogDocument> dogDocuments(MongoTemplate mongo, Clock clock) {
        var repo = new CensusRepository<>(mongo, DogDocument.class, clock); repo.documentIndex(); return repo;
    }
}

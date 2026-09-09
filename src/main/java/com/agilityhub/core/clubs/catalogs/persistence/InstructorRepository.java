package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class InstructorRepository extends TenantRepository<Instructor> {
    public InstructorRepository(MongoTemplate mongo) {
        super(mongo, Instructor.class);
        mongo.indexOps(Instructor.class).ensureIndex(new Index().on("clubId", Direction.ASC).on("memberId", Direction.ASC).unique());
        mongo.indexOps("team_write_locks").ensureIndex(new Index().on("clubId", Direction.ASC).unique());
    }
    /** All team writers share one transactional write, preventing last-admin write skew. */
    public void lock() { mongo.upsert(tenantQuery(), new Update().inc("sequence", 1), "team_write_locks"); }
    public Optional<Instructor> forMember(String memberId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId)), Instructor.class));
    }
}

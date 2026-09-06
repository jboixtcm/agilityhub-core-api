package com.agilityhub.core.platform.persistence;

import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.util.Optional;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
public class ClubRepository extends GlobalRepository<Club> {
    public ClubRepository(MongoTemplate mongo) { super(mongo, Club.class); }
    public Optional<Club> findByHost(String host) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("domains").elemMatch(
                Criteria.where("host").is(host).and("status").is(Club.DomainStatus.VERIFIED))), Club.class));
    }
    public void ensureIndexes() {
        mongo.indexOps(Club.class).ensureIndex(new Index().on("slug", Direction.ASC).unique().named("club_slug"));
        mongo.indexOps(Club.class).ensureIndex(new Index().on("domains.host", Direction.ASC).unique().sparse().named("club_host"));
    }
}

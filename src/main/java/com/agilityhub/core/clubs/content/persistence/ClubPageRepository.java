package com.agilityhub.core.clubs.content.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.shared.domain.*;
import java.util.Optional;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Repository;

@Repository
public class ClubPageRepository extends TenantRepository<ClubPage> {
    public ClubPageRepository(MongoTemplate mongo) {
        super(mongo, ClubPage.class);
        mongo.indexOps(ClubPage.class).ensureIndex(new Index().on("clubId", Direction.ASC).on("key", Direction.ASC).unique());
    }
    public Optional<ClubPage> findByKey(String key) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("key").is(key)), ClubPage.class));
    }
    public void update(ClubPage next, long revision) {
        var query = tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("revision").is(revision));
        if (mongo.findAndReplace(query, next, FindAndReplaceOptions.options().returnNew()) == null) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }
}

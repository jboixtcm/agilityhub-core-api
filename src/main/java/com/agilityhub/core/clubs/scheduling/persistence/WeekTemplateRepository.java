package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class WeekTemplateRepository extends TenantRepository<WeekTemplate> {
    public WeekTemplateRepository(MongoTemplate mongo) { super(mongo, WeekTemplate.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(WeekTemplate.class).ensureIndex(new Index().on("clubId", ASC).on("kind", ASC).on("name", ASC).unique().collation(Collation.of("en").strength(Collation.ComparisonLevel.secondary())).named("template_club_kind_name"));
    }

    public WeekTemplate update(WeekTemplate next, long expectedVersion) {
        var query = tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expectedVersion));
        var result = mongo.findAndReplace(query, next, org.springframework.data.mongodb.core.FindAndReplaceOptions.options().returnNew());
        if (result == null) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION); }
        return result;
    }
}

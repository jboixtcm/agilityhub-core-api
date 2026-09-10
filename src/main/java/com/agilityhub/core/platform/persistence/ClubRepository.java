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
    public Optional<Club> findByAnyHost(String host) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("domains.host").is(host)), Club.class));
    }
    public Optional<Club> findBySlug(String slug) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("slug").is(slug)), Club.class));
    }
    public int nextMemberNumber(int minimum) {
        var query=Query.query(Criteria.where("_id").is(com.agilityhub.core.shared.application.TenantContext.require()));
        var club=mongo.findOne(query,org.bson.Document.class,"clubs");
        if(club==null) throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.NOT_FOUND);
        long reserved=((Number)club.getOrDefault("nextMemberNumber",1L)).longValue();
        return new MemberNumberSequenceRepository(mongo).next(Math.max(reserved,minimum));
    }
    public void reserveMemberNumbers(int maximum) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(com.agilityhub.core.shared.application.TenantContext.require())),
                new org.springframework.data.mongodb.core.query.Update().max("nextMemberNumber", (long) maximum + 1), Club.class);
    }
    public void ensureIndexes() {
        mongo.indexOps(Club.class).ensureIndex(new Index().on("slug", Direction.ASC).unique().named("club_slug"));
        mongo.indexOps(Club.class).ensureIndex(new Index().on("domains.host", Direction.ASC).unique().sparse().named("club_host"));
    }
}

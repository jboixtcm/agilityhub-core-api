package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Clock;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

/** Partial migration writes preserve fields subsequently maintained by club administrators. */
@Repository
public class CensusMigrationRepository extends TenantRepository<Member> {
    private final Clock clock;
    public CensusMigrationRepository(MongoTemplate mongo, Clock clock) { super(mongo,Member.class); this.clock=clock; }
    public List<Map<String,Object>> snapshot(String collection) {
        check(collection); return mongo.find(tenantQuery(),Document.class,collection).stream().map(d -> (Map<String,Object>) d).toList();
    }
    public void write(String collection, String id, Map<String,Object> fields) {
        check(collection);
        var query=tenantQuery().addCriteria(Criteria.where("_id").is(id));
        var old=mongo.findOne(query,Document.class,collection);
        if (old!=null && (old.get("sourceIds")==null || "ERASED".equals(old.getString("status")) || old.get("erasedAt")!=null)) { throw new ApiException(ErrorCode.CLUB_NOT_EMPTY); }
        var update=new Update().setOnInsert("clubId",TenantContext.require()).setOnInsert("createdAt",clock.instant())
                .set("updatedAt",clock.instant()).inc("version",1);
        fields.forEach((key,value) -> { if (value==null) { update.unset(key); } else { update.set(key,value); } });
        mongo.upsert(query,update,collection);
    }
    public void lock() {
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(TenantContext.require()+":census")),new Update().inc("sequence",1),"census_write_locks");
    }
    private void check(String collection) {
        if (!Set.of("members","dogs","family_groups").contains(collection)) { throw new IllegalArgumentException("Unsupported census collection"); }
    }
}

package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.courses.domain.CourseParts.RingGeometry;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** S16 owns only rings.geometry. The S05 ring version and every catalog field are untouched. */
@Repository
public class RingGeometryRepository extends TenantRepository<RingGeometryRepository.GeometryRecord> {
    @org.springframework.data.mongodb.core.mapping.Document("rings")
    public record GeometryRecord(@Id String id, String clubId, RingGeometry geometry) implements TenantEntity { }
    public RingGeometryRepository(MongoTemplate mongo) { super(mongo, GeometryRecord.class); }
    public void write(String id, RingGeometry geometry, long expectedVersion) {
        var version = expectedVersion == 0 ? new Criteria().orOperator(Criteria.where("geometry").is(null),
                Criteria.where("geometry.version").is(0L)) : Criteria.where("geometry.version").is(expectedVersion);
        var query = tenantQuery().addCriteria(Criteria.where("_id").is(id)).addCriteria(version);
        if (mongo.updateFirst(query, new Update().set("geometry", geometry), GeometryRecord.class).getMatchedCount() != 1) {
            throw new ApiException(ErrorCode.STALE_VERSION);
        }
    }
    @Override public GeometryRecord insert(GeometryRecord value) { throw new UnsupportedOperationException("S05 creates rings"); }
    @Override public GeometryRecord replace(GeometryRecord value) { throw new UnsupportedOperationException("Use the geometry field update"); }
    @Override public boolean deleteById(String id) { throw new UnsupportedOperationException("S05 owns rings"); }
}

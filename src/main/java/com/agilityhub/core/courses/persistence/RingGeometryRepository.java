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

/**
 * S16 owns only rings.geometry, rings.activeSetupId and rings.geometryReferences. The S05 ring version and every catalog field are
 * untouched.
 */
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
    /** R-16-04 `DELETE`: removes the geometry at the expected version; another version is `409 STALE_VERSION`. */
    public void clear(String id, long expectedVersion) {
        var query = tenantQuery().addCriteria(Criteria.where("_id").is(id).and("geometry.version").is(expectedVersion));
        if (mongo.updateFirst(query, new Update().unset("geometry"), GeometryRecord.class).getMatchedCount() != 1) {
            throw new ApiException(ErrorCode.STALE_VERSION);
        }
    }
    /**
     * E115 (write skew, E9-T02 review #7): a placement or setup write that relies on the ring's geometry writes the ring document too,
     * at the geometry version it read (`$inc geometryReferences`, always a real write). A concurrent change or removal of the geometry
     * writes the same document, so one of the two transactions gets a write conflict instead of leaving a placement on a geometry
     * that is gone or changed unseen. Another version (changed meanwhile) is `409 STALE_VERSION`.
     */
    public void reference(String id, long geometryVersion) {
        var query = tenantQuery().addCriteria(Criteria.where("_id").is(id).and("geometry.version").is(geometryVersion));
        if (mongo.updateFirst(query, new Update().inc("geometryReferences", 1), GeometryRecord.class).getMatchedCount() != 1) {
            throw new ApiException(ErrorCode.STALE_VERSION);
        }
    }
    /** R-16-07: `Ring.activeSetupId` (S16's other ring field) points to the ring's new active setup. */
    public void pointActiveSetup(String id, String setupId) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("activeSetupId", setupId), GeometryRecord.class);
    }
    /** R-16-08: `Ring.activeSetupId = null`, only while it still points to {@code setupId}. */
    public void releaseActiveSetup(String id, String setupId) {
        mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("activeSetupId").is(setupId)), new Update().set("activeSetupId", null), GeometryRecord.class);
    }
    @Override public GeometryRecord insert(GeometryRecord value) { throw new UnsupportedOperationException("S05 creates rings"); }
    @Override public GeometryRecord replace(GeometryRecord value) { throw new UnsupportedOperationException("Use the geometry field update"); }
    @Override public boolean deleteById(String id) { throw new UnsupportedOperationException("S05 owns rings"); }
}

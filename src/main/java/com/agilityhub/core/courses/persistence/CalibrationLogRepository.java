package com.agilityhub.core.courses.persistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.*;

@Repository
public class CalibrationLogRepository extends CourseTenantRepository<CalibrationLog> {
    public CalibrationLogRepository(MongoTemplate mongo) {
        super(mongo, CalibrationLog.class);
        mongo.indexOps(CalibrationLog.class).createIndex(new Index().on("clubId", ASC).on("ringId", ASC).on("createdAt", DESC).named("calibration_ring"));
    }
    /** §14.2: the calibrations of a ring, newest first. */
    public java.util.List<CalibrationLog> forRing(String ringId) {
        return mongo.find(tenantQuery().addCriteria(org.springframework.data.mongodb.core.query.Criteria.where("ringId").is(ringId))
                .with(org.springframework.data.domain.Sort.by(DESC, "createdAt", "_id")), CalibrationLog.class);
    }
}

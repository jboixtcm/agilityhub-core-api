package com.agilityhub.core.courses.persistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.*;

@Repository
public class CalibrationLogRepository extends CourseTenantRepository<CalibrationLog> {
    public CalibrationLogRepository(MongoTemplate mongo) {
        super(mongo, CalibrationLog.class);
        mongo.indexOps(CalibrationLog.class).ensureIndex(new Index().on("clubId", ASC).on("ringId", ASC).on("createdAt", DESC).named("calibration_ring"));
    }
}

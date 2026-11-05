package com.agilityhub.core.clubs.scheduling.persistence;

import java.time.DayOfWeek;
import java.util.List;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Freeze the currently referenced bands once for weeks generated before band snapshots existed. */
@Component
public class WeekTimeBandBackfill implements ApplicationRunner {
    private final MongoTemplate mongo;
    public WeekTimeBandBackfill(MongoTemplate mongo) { this.mongo = mongo; }

    @Override public void run(ApplicationArguments args) {
        var weeks = mongo.getCollection("weeks");
        var filter = new Document("generatedAt", new Document("$ne", null)).append("dailyTimeBands", null);
        for (var week : weeks.find(filter)) {
            var daily = new Document();
            for (var day : DayOfWeek.values()) {
                var key = day == DayOfWeek.SATURDAY ? "saturdayTemplateId" : "weekdayTemplateId";
                var id = day == DayOfWeek.SUNDAY ? null : week.getString(key);
                var template = id == null ? null : mongo.getCollection("week_templates").find(
                        new Document("_id", id).append("clubId", week.getString("clubId"))).first();
                daily.put(day.name(), template == null ? List.of() : template.getList("timeBands", Document.class, List.of()));
            }
            // A concurrent generation or edit wins; a subsequent start can retry an unchanged legacy week.
            var guard = new Document(filter).append("_id", week.get("_id")).append("version", week.get("version"));
            weeks.updateOne(guard, new Document("$set", new Document("dailyTimeBands", daily))
                    .append("$inc", new Document("version", 1L)));
        }
    }
}

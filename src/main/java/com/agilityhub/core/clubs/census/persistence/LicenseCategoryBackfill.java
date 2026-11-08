package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.clubs.census.domain.LicenseCategories;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Idempotent initialization of legacy federation categories, guarded against concurrent edits. */
@Component
public class LicenseCategoryBackfill implements ApplicationRunner {
    private final MongoTemplate mongo;
    public LicenseCategoryBackfill(MongoTemplate mongo) { this.mongo = mongo; }

    @Override public void run(ApplicationArguments args) {
        // CLI previews and dry runs must not initialize stored records as a startup side effect.
        if (args != null && args.containsOption("core.command")) return;
        var filter = new Document("erasedAt", null).append("licenses", new Document("$elemMatch",
                new Document("organisation", new Document("$regex", "^FCAG$").append("$options", "i")).append("category", null)));
        for (var dog : mongo.getCollection("dogs").find(filter)) {
            List<Map<String, Object>> before = dog.getList("licenses", Document.class).stream()
                    .map(row -> (Map<String, Object>) row).toList();
            var after = LicenseCategories.initialize(before);
            if (before.equals(after)) continue;
            var guard = new Document(filter).append("_id", dog.get("_id"))
                    .append("clubId", dog.get("clubId")).append("version", dog.get("version"));
            // Preserve the reset/write-tracking fence and invalidate existing editor versions.
            mongo.updateFirst(new BasicQuery(guard), new Update().set("licenses", after).inc("version", 1L), "dogs");
        }
    }
}

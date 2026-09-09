package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.catalogs.persistence.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.domain.Sort.Direction;

@Configuration
public class CatalogConfiguration {
    @Bean CatalogRepository<Level> levelsRepository(MongoTemplate mongo) {
        mongo.indexOps(Level.class).ensureIndex(new Index().on("clubId", Direction.ASC).on("code", Direction.ASC)
                .unique().collation(Collation.of("en").strength(2)).named("level_code"));
        mongo.indexOps(Level.class).ensureIndex(new Index().on("clubId", Direction.ASC).on("nameKeys", Direction.ASC)
                .unique().partial(PartialIndexFilter.of(Criteria.where("active").is(true))).named("active_level_names"));
        return new CatalogRepository<>(mongo, Level.class);
    }
    @Bean CatalogRepository<Ring> ringsRepository(MongoTemplate mongo) {
        for (String field : java.util.List.of("name", "shortName")) {
            mongo.indexOps(Ring.class).ensureIndex(new Index().on("clubId", Direction.ASC).on(field, Direction.ASC)
                    .unique().collation(Collation.of("en").strength(2)).named("ring_" + field));
        }
        return new CatalogRepository<>(mongo, Ring.class);
    }
    @Bean CatalogRepository<FaqEntry> faqsRepository(MongoTemplate mongo) {
        mongo.indexOps(FaqEntry.class).ensureIndex(new Index().on("clubId", Direction.ASC).on("order", Direction.ASC));
        return new CatalogRepository<>(mongo, FaqEntry.class);
    }
}

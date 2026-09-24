package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/** S10 §3 `tasks`; the use cases arrive with E6-T03. */
@Repository
public class TaskRepository extends TenantRepository<Task> {
    public TaskRepository(MongoTemplate mongo) { super(mongo, Task.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        mongo.indexOps(Task.class).ensureIndex(new Index().on("clubId", ASC).on("dogId", ASC).on("deletedAt", ASC).on("state", ASC).on("createdAt", ASC)
                .named("task_club_dog_deleted_state_created"));
    }
}

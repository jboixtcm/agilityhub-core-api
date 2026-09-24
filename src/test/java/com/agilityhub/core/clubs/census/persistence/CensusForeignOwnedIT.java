package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AbstractIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.*;

/** E5-T11 step 3: a census save never writes a {@link ForeignOwned} field, and fails fast when one was changed in memory. */
class CensusForeignOwnedIT extends AbstractIntegrationTest {
    static final String CLUB = "e5t11-census";
    @Autowired MongoTemplate mongo;
    @Autowired CensusRepository<Member> members;

    @BeforeEach void clean() {
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "members");
        mongo.insert(new Document("_id", "e5t11-m").append("clubId", CLUB).append("firstName", "Example").append("status", "ACTIVE")
                .append("lastDogForClass", "e5t11-d1").append("version", 3L), "members");
    }
    Document stored() { return mongo.findById("e5t11-m", Document.class, "members"); }

    @Test void R_08_23_aSaveThatChangedAForeignOwnedFieldFailsFastAndWritesNothing() {
        try (var t = TenantContext.open(CLUB)) {
            var member = members.require("e5t11-m");
            member.firstName = "Changed"; member.lastDogForClass = "e5t11-d2";
            assertThatThrownBy(() -> members.save(member)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Member.lastDogForClass is @ForeignOwned");
            var cleared = members.require("e5t11-m"); cleared.lastDogForTraining = "e5t11-d3";
            assertThatThrownBy(() -> members.save(cleared)).as("null → value is a write too").isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lastDogForTraining");
        }
        assertThat(stored()).containsEntry("firstName", "Example").containsEntry("lastDogForClass", "e5t11-d1").containsEntry("version", 3L)
                .doesNotContainKey("lastDogForTraining");
    }

    @Test void R_08_23_aForeignFieldWrittenBySetFieldAfterTheReadIsKeptByTheSave() {
        try (var t = TenantContext.open(CLUB)) {
            var member = members.require("e5t11-m");
            members.setField("e5t11-m", "lastDogForClass", "e5t11-d2"); // S08 books meanwhile: no version bump
            member.firstName = "Changed";
            members.save(member);
        }
        assertThat(stored()).containsEntry("firstName", "Changed").containsEntry("lastDogForClass", "e5t11-d2").containsEntry("version", 4L);
    }

    @Test void R_08_23_anEntityNotReadFromMongoIsComparedWithTheStoredValue() {
        try (var t = TenantContext.open(CLUB)) {
            var detached = new Member(); detached.id = "e5t11-m"; detached.clubId = CLUB; detached.version = 3L; detached.firstName = "Detached";
            assertThatThrownBy(() -> members.save(detached)).as("stored d1, in memory null").isInstanceOf(IllegalStateException.class);
            detached.lastDogForClass = "e5t11-d1";
            members.save(detached);
        }
        assertThat(stored()).containsEntry("firstName", "Detached").containsEntry("lastDogForClass", "e5t11-d1").containsEntry("version", 4L);
    }
}

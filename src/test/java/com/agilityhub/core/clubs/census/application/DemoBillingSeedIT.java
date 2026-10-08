package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.platform.application.definition.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

/**
 * E8-T06 steps 7, 11 and 12: `seed:demo` adds the E8 cases through the application services — the fictional SEPA creditor,
 * a pending inactivity request, an active inactivity with its fee snapshot, a planned leave on the next 31-08, an expired
 * 10-session pack with its planned leave, `nextInvoiceDate` on every active monthly member, and on the FIFO club a CARD payer
 * and two single-class charges — and a second run changes nothing (T-06-28's idempotence, now with the billing section).
 */
@TestPropertySource(properties = "identity.seed-password=Fictional-seed-password")
class DemoBillingSeedIT extends AbstractIntegrationTest {
    @Autowired ClubDefinitions definitions; @Autowired ClubDefinitionCodec codec; @Autowired DemoSeedCommand command;
    @Autowired MongoTemplate mongo; @Autowired ClubConfigService configs;
    String club;
    @BeforeEach void clear() {
        wipeDatabaseKeepingBootstrap(); clock.setInstant(Instant.parse("2026-09-09T10:00:00Z"));
        club = definitions.apply(codec.read(Path.of("seeds/club-canic.yaml")), false).id();
    }
    void seed(String slug) { command.run(new DefaultApplicationArguments("--club=" + slug, "--seed=42", "--week-start=2026-09-07")); }
    Document member(String clubId, int number) {
        return mongo.findOne(Query.query(Criteria.where("clubId").is(clubId).and("memberNumber").is(number)), Document.class, "members");
    }
    List<Document> of(String collection, String memberId) { return mongo.find(Query.query(Criteria.where("memberId").is(memberId)), Document.class, collection); }
    Map<String, List<Document>> snapshot() {
        var result = new TreeMap<String, List<Document>>();
        for (String name : mongo.getCollectionNames()) { result.put(name, mongo.findAll(Document.class, name)); }
        return result;
    }

    @Test void T_12_14_T_13_27_theDemoSeedHoldsTheE8CasesThroughTheServicesAndRepeatsWithoutWrites() {
        seed("canic");
        var sepa = mongo.findById(club, Document.class, "clubs").get("paymentProviders", Document.class).get("SEPA_XML", Document.class);
        assertThat(sepa).containsEntry("enabled", true).containsEntry("creditorName", "Club Agility Exemple").containsEntry("creditorId", "ES64ZZZ999999999");
        // Ordinal n is member number n + 1.
        var pending = of("inactivity_periods", member(club, 164).getString("_id"));
        assertThat(pending).singleElement().satisfies(p -> {
            assertThat(p).containsEntry("state", "REQUESTED").containsEntry("fromMonth", "2026-10"); assertThat(p.get("toMonth")).isNull();
        });
        var active = of("inactivity_periods", member(club, 157).getString("_id"));
        assertThat(active).singleElement().satisfies(p -> {
            assertThat(p).containsEntry("state", "ACTIVE").containsEntry("fromMonth", "2026-09").containsEntry("toMonth", "2026-10");
            assertThat(p.get("feeSnapshot")).isNotNull();
        });
        var leaving = member(club, 153);
        assertThat(leaving.getString("status")).isEqualTo("ACTIVE"); assertThat(leaving.get("leaveDate").toString()).isEqualTo("2027-08-31");
        var packMember = member(club, 173);
        assertThat(of("pack_balances", packMember.getString("_id"))).singleElement().satisfies(p -> {
            assertThat(p).containsEntry("state", "EXPIRED").containsEntry("expiresOn", "2026-09-08"); assertThat(p.getInteger("sessionsTotal")).isEqualTo(10);
        });
        assertThat(packMember.get("leaveDate")).isNotNull();
        assertThat(of("leave_requests", packMember.getString("_id"))).singleElement().satisfies(l -> assertThat(l.getString("source")).isEqualTo("PACK_EXPIRED"));
        // E8-T02's assumption 18: every active monthly member has its next invoice date, so the smoke needs no hand-made dates.
        var monthly = new HashSet<String>();
        mongo.find(Query.query(Criteria.where("clubId").is(club).and("type").is("MONTHLY")), Document.class, "plans").forEach(p -> monthly.add(p.getString("_id")));
        var active184 = mongo.find(Query.query(Criteria.where("clubId").is(club).and("status").is("ACTIVE")), Document.class, "members");
        assertThat(active184).filteredOn(m -> monthly.contains(m.getString("planId"))).isNotEmpty()
                .allSatisfy(m -> assertThat(m.get("nextInvoiceDate").toString()).isEqualTo("2026-10-01"));

        String fifo = definitions.apply(codec.read(Path.of("seeds/club-fifo.yaml")), false).id();
        seed("fifo");
        assertThat(member(fifo, 8).get("paymentMethod", Document.class)).containsEntry("type", "CARD");
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(fifo)), Document.class, "pending_charges")).hasSize(2)
                .allSatisfy(c -> {
                    assertThat(c.getString("memberId")).isEqualTo(member(fifo, 6).getString("_id"));
                    var booking = mongo.findById(c.getString("bookingId"), Document.class, "bookings");
                    assertThat(booking).as("Pending charge references a real booking").isNotNull();
                    assertThat(booking).containsEntry("memberId", c.getString("memberId")).containsEntry("dogId", c.getString("dogId"));
                    assertThat(mongo.findById(booking.getString("classSessionId"), Document.class, "class_sessions")).isNotNull();
                    assertThat(mongo.findOne(Query.query(Criteria.where("bookingId").is(booking.getString("_id"))), Document.class, "attendances"))
                            .containsEntry("state", "PRESENT");
                });
        // The FIFO club's section does not reach the Cànic.
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(club)), "pending_charges")).isZero();

        var saved = snapshot(); seed("canic"); seed("fifo");
        assertThat(snapshot()).isEqualTo(saved);
    }
}

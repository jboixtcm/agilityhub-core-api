package com.agilityhub.core.configuration;

import com.agilityhub.core.courses.persistence.*;
import com.agilityhub.core.courses.domain.CourseParts.RingGeometry;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;

class E9PersistenceIT extends AbstractIntegrationTest {
    static final String CLUB="e9p-a", OTHER="e9p-b";
    static final List<Class<?>> TYPES=List.of(Course.class,Placement.class,RingSetup.class,BuildSession.class,ObstacleInventory.class,CalibrationLog.class,Venue.class);
    @Autowired MongoTemplate mongo;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired CourseRepository courses;
    @Autowired com.agilityhub.core.shared.persistence.IdempotencyRepository idempotency;
    @Autowired GlobalCourseRepository global;
    @Autowired RingGeometryRepository geometry;
    @Autowired com.agilityhub.core.clubs.catalogs.persistence.CatalogRepository<com.agilityhub.core.clubs.catalogs.persistence.Ring> rings;
    @BeforeEach void clean() {
        for(var type:TYPES) mongo.remove(Query.query(new Criteria().orOperator(Criteria.where("clubId").in(CLUB,OTHER),Criteria.where("_id").regex("^e9p-"))),type);
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB,OTHER)),"rings");
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    @Test void T_16_05_sevenRepositoriesBindEveryReadAndWriteToTheCurrentTenant() throws Exception {
        for(var type:TYPES) {
            var repository=(CourseTenantRepository)context.getBean(Class.forName(type.getName()+"Repository"));
            var a=(TenantEntity)E9Fixtures.document(type,"e9p-"+type.getSimpleName(),CLUB);
            assertThatThrownBy(()->repository.findById(a.id())).isInstanceOf(ApiException.class);
            assertThatThrownBy(()->repository.insert(a)).isInstanceOf(ApiException.class);
            try(var scope=TenantContext.open(CLUB)) { repository.insert(a);assertThat(repository.findById(a.id())).contains(a); }
            try(var scope=TenantContext.open(OTHER)) {
                assertThat(repository.findById(a.id())).isEmpty();assertThat(repository.findAll()).isEmpty();
                assertThatThrownBy(()->repository.insert(a)).isInstanceOf(ApiException.class);
                assertThatThrownBy(()->repository.replace(a)).isInstanceOf(ApiException.class);
                var forged=(TenantEntity)E9Fixtures.document(type,a.id(),OTHER);
                assertThatThrownBy(()->repository.replace(forged)).isInstanceOf(DuplicateKeyException.class);
                assertThatThrownBy(()->repository.deleteById(a.id())).isInstanceOf(UnsupportedOperationException.class);
            }
            try(var scope=TenantContext.open(CLUB)) { assertThat(repository.findById(a.id())).contains(a); repository.replace(a);assertThat(repository.findById(a.id())).contains(a); }
        }
    }
    @Test void T_16_03_rawMetadataAndHistoryRetainDotsDollarsAndJsonWithoutConversion() throws Exception {
        var original=E9Fixtures.document(Course.class,"e9p-course",CLUB);
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(original);
        json.withArray("history").add(mapper.createObjectNode().put("version",0).put("schemaVersion",1).set("normalizedJson",original.normalizedJson()));
        // A9: keys that extended JSON would reinterpret stay plain keys with their own values.
        var metadata=(com.fasterxml.jackson.databind.node.ObjectNode)json.path("normalizedJson").path("rawMetadata");
        metadata.put("$date",0);metadata.put("$numberLong","not-a-number");metadata.putObject("when").put("$date",0);metadata.putObject("nested").put("$oid","bad").put("exact",new java.math.BigDecimal("0.10"));
        original=mapper.treeToValue(json,Course.class);
        try(var scope=TenantContext.open(CLUB)) {
            courses.insert(original);var stored=courses.findById(original.id()).orElseThrow();
            assertThat(stored).isEqualTo(original);assertThat(stored.schemaVersion()).isEqualTo(1);
            assertThat(stored.normalizedJson().path("rawMetadata").path("planner.version").asInt()).isEqualTo(1);
            assertThat(stored.normalizedJson().path("rawMetadata").has("$original")).isTrue();
            var raw=mongo.findById(original.id(),Document.class,"courses");
            assertThat(raw.get("normalizedJson",Document.class).get("rawMetadata",Document.class)).containsKeys("planner.version","$original")
                    .containsEntry("$date",0).containsEntry("$numberLong","not-a-number");
            assertThat(stored.normalizedJson().at("/rawMetadata/$date").isInt()).isTrue();
            assertThat(stored.normalizedJson().at("/rawMetadata/when/$date").isInt()).isTrue();
            assertThat(stored.normalizedJson().at("/rawMetadata/nested/$oid").asText()).isEqualTo("bad");
            assertThat(stored.normalizedJson().at("/rawMetadata/nested/exact").decimalValue()).isEqualByComparingTo("0.10");
        }
        var publicCourse=E9Fixtures.document(Course.class,"e9p-public",null);mongo.insert(publicCourse);
        assertThat(global.findById(publicCourse.id())).contains(publicCourse);
        assertThat(global.findById(original.id())).isEmpty();
        assertThatThrownBy(()->global.save(publicCourse)).isInstanceOf(UnsupportedOperationException.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(publicCourse.id())),new Update().set("deletedAt",clock.instant()),Course.class);
        assertThat(global.findById(publicCourse.id())).isEmpty();
    }
    @Test void T_16_06_calendarDatesStayDateOnlyAcrossDstBoundaries() throws Exception {
        Map<Class<?>,String> fields=Map.of(Course.class,"designedOn",RingSetup.class,"expectedUntil",Venue.class,"partnerSince");
        for(var entry:fields.entrySet()) {
            var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(E9Fixtures.document(entry.getKey(),"e9p-dates-"+entry.getKey().getSimpleName(),CLUB));
            json.put(entry.getValue(),"2026-10-25");if(entry.getKey()==Venue.class) json.put("partnerUntil","2027-03-28");
            var value=mapper.treeToValue(json,entry.getKey());mongo.insert(value);
            var raw=mongo.findById(json.path("id").asText(),Document.class,mongo.getCollectionName(entry.getKey()));
            assertThat(raw.get(entry.getValue())).isEqualTo("2026-10-25");
            assertThat(mongo.findById(json.path("id").asText(),entry.getKey())).isEqualTo(value);
        }
    }
    @Test void T_16_21_platformIdempotencyIsIsolatedFromClubAndOtherAccountClaims() {
        String key=UUID.randomUUID().toString();
        try {
            var first=idempotency.claim(null,"e9p-account",key,"fictional-request",clock.instant());
            assertThat(first.acquired()).isTrue();assertThat(first.record().clubId()).isNull();
            idempotency.lock(first.record());idempotency.complete(first.record(),201,new byte[]{1},Map.of());
            var replay=idempotency.claim(null,"e9p-account",key,"fictional-request",clock.instant());
            assertThat(replay.acquired()).isFalse();assertThat(replay.record().responseStatus()).isEqualTo(201);
            assertThat(idempotency.claim(null,"e9p-other-account",key,"fictional-request",clock.instant()).acquired()).isTrue();
            try(var scope=TenantContext.open(CLUB)) {
                assertThat(idempotency.claim(CLUB,"e9p-account",key,"fictional-request",clock.instant()).acquired()).isTrue();
                assertThatThrownBy(()->idempotency.claim(null,"e9p-account",key,"fictional-request",clock.instant()))
                        .isInstanceOfSatisfying(ApiException.class,ex->assertThat(ex.code()).isEqualTo(ErrorCode.TENANT_MISMATCH));
                assertThatThrownBy(()->idempotency.held(first.record())).isInstanceOf(ApiException.class);
            }
        } finally { mongo.remove(Query.query(Criteria.where("key").is(key)),"idempotency_records"); }
    }
    @Test void T_16_03_rawJsonFieldsPreserveArraysObjectsAndExplicitNull() throws Exception {
        for(String raw:List.of("null", "[{\"planner.key\":{\"$value\":true}},2,null]", "{\"position\":1.25}")) {
            var session=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(E9Fixtures.document(BuildSession.class,"e9p-json",CLUB));
            session.set("progressJson",mapper.readTree(raw));var value=mapper.treeToValue(session,BuildSession.class);
            mongo.remove(Query.query(Criteria.where("_id").is(value.id())),BuildSession.class);mongo.insert(value);
            assertThat(mongo.findById(value.id(),BuildSession.class).progressJson()).isEqualTo(mapper.readTree(raw));
        }
    }
    @Test void T_16_07_T_16_11_fourUniqueGuardsAreRealMongoIndexesAndReleaseOnlyClosedRows() throws Exception {
        for(String collection:List.of("ring_setups","build_sessions","obstacle_inventories","venues")) {
            Document a=new Document("_id","e9p-"+collection+"-a").append("clubId",CLUB).append("ringId","ring-a").append("scope","RING")
                    .append("status",collection.equals("build_sessions")?"NOT_STARTED":"ACTIVE").append("joinCode","DEMO42").append("slug","e9p-"+collection);
            mongo.insert(a,collection);var duplicate=new Document(a);duplicate.put("_id","e9p-"+collection+"-duplicate");
            if(collection.equals("venues")) duplicate.put("slug","different-slug");
            assertThatThrownBy(()->mongo.insert(duplicate,collection)).isInstanceOf(DuplicateKeyException.class);
            var other=new Document(duplicate);other.put("_id","e9p-"+collection+"-other");other.put("clubId",OTHER);other.put("slug","e9p-other-"+collection);mongo.insert(other,collection);
            if(collection.equals("ring_setups")||collection.equals("build_sessions")) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(a.getString("_id"))),new Update().set("status",collection.equals("ring_setups")?"DISMANTLED":"COMPLETED"),collection);
                mongo.insert(duplicate,collection);
                if(collection.equals("build_sessions")) {
                    mongo.updateFirst(Query.query(Criteria.where("_id").is(duplicate.getString("_id"))),new Update().set("status","IN_PROGRESS"),collection);
                    var open=new Document(duplicate);open.put("_id","e9p-new-open");assertThatThrownBy(()->mongo.insert(open,collection)).isInstanceOf(DuplicateKeyException.class);
                }
            }
            if(collection.equals("venues")) {
                var sameSlug=new Document(other);sameSlug.put("_id","e9p-slug");sameSlug.put("clubId","different-club");sameSlug.put("slug",a.get("slug"));
                assertThatThrownBy(()->mongo.insert(sameSlug,collection)).isInstanceOf(DuplicateKeyException.class);
                mongo.insert(new Document("_id","e9p-unowned-1").append("slug","e9p-unowned-1"),collection);
                mongo.insert(new Document("_id","e9p-unowned-2").append("slug","e9p-unowned-2"),collection);
            }
        }
    }
    @Test void T_16_06_ringGeometryAndCatalogEditsPreserveEachOthersFieldsAndVersions() throws Exception {
        var ring=new com.agilityhub.core.clubs.catalogs.persistence.Ring("e9p-ring",CLUB,"Before","R","#123456",true,8,1,true,7,
                clock.instant(),clock.instant(),"actor-a","actor-a","e9p-active-setup");
        var value=mapper.treeToValue(E9Fixtures.response("RingGeometry"),RingGeometry.class);
        try(var scope=TenantContext.open(CLUB)) {
            rings.insert(ring);geometry.write(ring.id(),value,0);
            assertThat(rings.findById(ring.id())).contains(ring);
            var edited=new com.agilityhub.core.clubs.catalogs.persistence.Ring(ring.id(),CLUB,"After","R","#654321",true,9,2,true,8,
                    clock.instant(),clock.instant(),"actor-a","actor-b",ring.activeSetupId());
            rings.update(edited,7);
            assertThat(geometry.findById(ring.id()).orElseThrow().geometry()).isEqualTo(value);
            assertThat(rings.findById(ring.id())).contains(edited);
            var next=new RingGeometry(value.lengthM()+1,value.widthM(),value.borderClearanceM(),value.orientationDeg(),value.surface(),value.notes(),
                    value.doors(),value.noGoZones(),value.markers(),2,clock.instant());geometry.write(ring.id(),next,1);
            assertThat(rings.findById(ring.id())).contains(edited);assertThat(geometry.findById(ring.id()).orElseThrow().geometry()).isEqualTo(next);
            assertThatThrownBy(()->geometry.write(ring.id(),value,1)).isInstanceOfSatisfying(ApiException.class,ex->assertThat(ex.code()).isEqualTo(ErrorCode.STALE_VERSION));
        }
        try(var scope=TenantContext.open(OTHER)) { assertThatThrownBy(()->geometry.write(ring.id(),value,2)).isInstanceOf(ApiException.class);assertThat(geometry.findById(ring.id())).isEmpty(); }
        assertThatThrownBy(()->geometry.insert(new RingGeometryRepository.GeometryRecord(ring.id(),CLUB,value))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->geometry.replace(new RingGeometryRepository.GeometryRecord(ring.id(),CLUB,value))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->geometry.deleteById(ring.id())).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void T_16_16_indexesMatchEveryDeclaredQueryAndMongoshListsAllSevenCollections() throws Exception {
        Map<String,List<String>> expected=Map.of("courses",List.of("clubId,deletedAt,updatedAt","ownerType,visibility,deletedAt","ownerAccountId,deletedAt"),
                "placements",List.of("clubId,ringId","clubId,courseId","clubId,activityId"),
                "ring_setups",List.of("clubId,ringId","clubId,ringId,builtAt","clubId,status,expiresAt","clubId,courseId,status","clubId,placementId,status"),
                "build_sessions",List.of("clubId,placementId","clubId,status,startedAt","clubId,joinCode"),
                "obstacle_inventories",List.of("clubId,scope,ringId"),"calibration_logs",List.of("clubId,ringId,createdAt"),"venues",List.of("clubId","slug"));
        for(var entry:expected.entrySet()) {
            var actual=new ArrayList<String>();for(var index:mongo.getCollection(entry.getKey()).listIndexes()) { actual.add(String.join(",",index.get("key",Document.class).keySet())); }
            assertThat(actual).as(entry.getKey()).containsAll(entry.getValue());
        }
        String script="const d=db.getSiblingDB('agilityhub_test'); "+mapper.writeValueAsString(new TreeSet<>(expected.keySet()))+
                ".forEach(c=>print(c+' '+EJSON.stringify(d.getCollection(c).getIndexes())))";
        var result=MONGO.execInContainer("mongosh","--quiet","--eval",script);
        assertThat(result.getExitCode()).isZero();System.out.println("mongosh --quiet --eval "+script+"\n"+result.getStdout());
    }
}

package com.agilityhub.core.migration;

import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

class PlayoffMigrationIT extends AbstractIntegrationTest {
    static final Path FIXTURE=Path.of("src/test/resources/fixtures/playoff");
    static final MappingConfig MAPPING=MappingConfig.load(null);
    static final String CLUB="playoff-test",OTHER="playoff-other";
    static final String BANK_KEY=Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
    @DynamicPropertySource static void bank(DynamicPropertyRegistry registry) { registry.add("core.migration.bank-key",() -> BANK_KEY); }
    @Autowired PlayoffImportService importer; @Autowired PlayoffPlanner planner; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @TempDir Path temp;
    @BeforeEach void seed() {
        TenantContext.clear(); clock.setInstant(Instant.parse("2026-09-09T10:00:00Z"));
        for (String collection:List.of("members","dogs","family_groups","accounts","memberships","clubs","parameters","levels","plans","prices","migration_runs",
                "migration_write_locks","census_write_locks","catalog_write_locks","audit_entries","domain_events","notifications","magic_link_tokens")) { mongo.remove(new Query(),collection); }
        clubs.save(PlatformFixtures.club(CLUB,CLUB+".example.test")); clubs.save(PlatformFixtures.club(OTHER,OTHER+".example.test")); configs.invalidate(CLUB); configs.invalidate(OTHER);
        for (String code:List.of("ABONAT","ABONAT_FAMILIAR","TERAPIA","PACK10","PACK6","INSTRUCTOR_FREE","COMPETICIO_1")) {
            String type=code.startsWith("PACK") ? "PACK" : "MONTHLY";
            mongo.insert(new Document("_id",code).append("clubId",CLUB).append("code",code).append("type",type)
                    .append("billingMode",code.equals("TERAPIA") ? "MAINTENANCE" : "MONTHLY_FEE").append("active",true),"plans");
            mongo.insert(new Document("_id","price-"+code).append("clubId",CLUB).append("planId",code)
                    .append("concept",type.equals("PACK") ? "PACK" : code.equals("TERAPIA") ? "MAINTENANCE_FEE" : "MONTHLY_FEE")
                    .append("validFrom","2020-01-01"),"prices");
        }
        for (String code:List.of("A","B","C","D","E","F","G","CADELLS")) {
            mongo.insert(new Document("_id","level-"+code).append("clubId",CLUB).append("code",code).append("active",true),"levels");
        }
    }
    PlayoffInput input() { return PlayoffInput.read(FIXTURE,MAPPING); }
    PlayoffPlanner.Plan preview(PlayoffInput input) { try(var tenant=TenantContext.open(CLUB)) { return planner.plan(input,MAPPING); } }
    MigrationReport apply() { return importer.importDirectory(FIXTURE,MAPPING,CLUB,false,false,false); }
    long incidents(MigrationReport report,String code) { return report.rows().stream().filter(r -> r.code().equals(code)).count(); }
    Document member(int ordinal) { String source=input().files().get("members").get(ordinal-1).get("id"); return mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("externalIds.playoff").is(source)),Document.class,"members"); }
    List<Document> rows(String collection) { return mongo.find(new Query(),Document.class,collection); }
    @Test void T_18_01_fixtureIncidentsSplitPeopleAndDogsWithoutSignupRejections() {
        var report=apply(); assertThat(report.hasErrors()).as(report.render()).isFalse();
        assertThat(report.count("members","CREATED")).isEqualTo(188); assertThat(report.count("dogs","CREATED")).isEqualTo(189);
        assertThat(incidents(report,"DOG_INFERRED")).isEqualTo(9); assertThat(incidents(report,"CHIP_MISSING")).isEqualTo(16);
        assertThat(incidents(report,"AGE_SUSPECT")).isEqualTo(2); assertThat(incidents(report,"NO_BANK_ACCOUNT")).isEqualTo(26);
        assertThat(incidents(report,"BIRTHDATE_SUSPECT")).isEqualTo(1); assertThat(member(27).get("birthDate")).isNull();
        assertThat(member(28).get("birthDate")).isEqualTo("2015-01-01");
        assertThat(member(1).get("_id")).isEqualTo(member(184).get("_id"));
        assertThat(mongo.count(Query.query(Criteria.where("memberId").is(member(1).get("_id"))),"dogs")).isEqualTo(2);
        assertThat(member(29).getString("lastName2")).doesNotContain("(");
        var dogs=rows("dogs");
        var licenseDog=dogs.stream().filter(d -> !((List<?>)d.get("licenses")).isEmpty()).findFirst().orElseThrow();
        assertThat(licenseDog.get("handlerName")).isNotNull();
        assertThat(((List<Document>)licenseDog.get("licenses"))).extracting(d -> d.getString("organisation")).containsExactly("RSCE","FCAG");
        assertThat(((List<Document>)licenseDog.get("licenses")).getFirst()).containsEntry("category","L").containsEntry("grade","2").containsEntry("division","1D");
        assertThat(licenseDog.get("levelId")).isEqualTo("level-A");
        assertThat(dogs.stream().filter(d -> d.get("levelId")==null)).isNotEmpty();
        assertThat(rows("audit_entries")).isNotEmpty();
    }
    @Test void T_18_02_activeNumbersWinOldLeaversAreSkippedAndNumbersReserved() {
        var report=apply(); assertThat(report.hasErrors()).isFalse(); assertThat(incidents(report,"NUMBER_CONFLICT")).isEqualTo(4);
        for (int i=185;i<=188;i++) { assertThat(member(i).get("memberNumber")).isNull(); assertThat(member(i)).containsEntry("status","LEFT").containsEntry("leftReason","MIGRATED"); }
        assertThat(member(190)).isNull(); assertThat(member(191)).isNull();
        int max=input().files().get("members").stream().mapToInt(r -> Integer.parseInt(r.get("number"))).max().orElseThrow();
        assertThat(mongo.findById(CLUB,Document.class,"clubs").get("nextMemberNumber")).isEqualTo((long)max+1);
        var modified=mutate(0,Map.of("status","Bloqueado")); var plan=preview(modified);
        assertThat((Map<?,?>)plan.changes().stream().filter(c -> c.entity().equals("members") && c.source().row()==2).findFirst().orElseThrow().fields().get("bookingBlock")).containsValue(true);
    }
    @Test void T_18_04_explicitPayerGroupsAndEncryptedBankHandoffWithoutMandates() throws Exception {
        var report=apply(); assertThat(report.hasErrors()).isFalse(); assertThat(report.count("familyGroups","CREATED")).isEqualTo(1);
        var group=rows("family_groups").getFirst(); assertThat(group.get("holderMemberId")).isEqualTo(member(61).get("_id"));
        assertThat(member(81).get("familyGroupId")).isEqualTo(group.get("_id"));
        var bank=(Document)member(50).get("paymentMethod"); assertThat(bank).containsKeys("ibanEncrypted","ibanLast4").doesNotContainKeys("iban","mandateRef","mandateSignedAt");
        byte[] encrypted=Base64.getDecoder().decode(bank.getString("ibanEncrypted"));
        var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(BANK_KEY),"AES"),
                new javax.crypto.spec.GCMParameterSpec(128,Arrays.copyOfRange(encrypted,0,12)));
        cipher.updateAAD((CLUB+":"+member(50).get("_id")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(new String(cipher.doFinal(Arrays.copyOfRange(encrypted,12,encrypted.length)),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(input().files().get("members").get(49).get("iban"));
        assertThat((Map<?,?>)member(30).get("paymentMethod")).doesNotContainKey("ibanEncrypted");
        assertThat(incidents(report,"IBAN_INVALID")).isEqualTo(1); assertThat(incidents(report,"CARD_NOT_MIGRATED")).isEqualTo(1);
        assertThat((Map<?,?>)member(38).get("paymentMethod")).containsValue("MANUAL");
    }
    @Test void T_18_06_sharedEmailsHaveOneOwnerAndNoMailIsSent() {
        var report=apply(); assertThat(report.hasErrors()).isFalse();
        assertThat(member(61).get("accountId")).isNotNull(); assertThat(member(81).get("accountId")).isNull();
        for (int i=1;i<=7;i++) { assertThat(member(i).get("accountId")).isNull(); }
        assertThat(rows("accounts")).allMatch(d -> Boolean.TRUE.equals(d.get("onboardingPending")) && "MIGRATION".equals(d.get("createdSource")) && d.get("passwordHash")==null);
        var membership=mongo.findOne(Query.query(Criteria.where("memberId").is(member(43).get("_id"))),Document.class,"memberships");
        assertThat((List<?>)membership.get("roles")).contains("MEMBER","INSTRUCTOR","ADMIN");
        assertThat((Map<?,?>)((Map<?,?>)member(43).get("consents")).get("privacyPolicy")).containsValue("LEGACY");
        assertThat(rows("notifications")).isEmpty(); assertThat(rows("magic_link_tokens")).isEmpty();
        assertThat(report.render()).doesNotContain("@","Surname","Example","ibanEncrypted");
    }
    @Test void T_18_08_reapplyUpdatesOnlyMappedRecordsAndDryRunWritesNothing() {
        var before=snapshot(); var dry=importer.importDirectory(FIXTURE,MAPPING,CLUB,true,false,false);
        assertThat(dry.hasErrors()).isFalse(); assertThat(snapshot()).isEqualTo(before);
        assertThat(apply().hasErrors()).isFalse();
        mongo.insert(new Document("_id","manual").append("clubId",CLUB).append("firstName","Manual Example").append("status","ACTIVE"),"members");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(member(50).get("_id"))),new Update().set("remarks","Keep manual remarks"),"members");
        var again=apply(); assertThat(again.hasErrors()).as(again.render()).isFalse();
        assertThat(again.count("members","CREATED")).isZero(); assertThat(again.count("dogs","CREATED")).isZero(); assertThat(again.count("familyGroups","CREATED")).isZero();
        assertThat(again.count("members","UPDATED")).isEqualTo(188); assertThat(member(50).get("remarks")).isEqualTo("Keep manual remarks");
        assertThat(mongo.findById("manual",Document.class,"members")).containsEntry("firstName","Manual Example").doesNotContainKey("sourceIds");
        assertThatThrownBy(() -> importer.importDirectory(FIXTURE,MAPPING,CLUB,false,true,false)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION));
        assertThat(importer.importDirectory(FIXTURE,MAPPING,CLUB,false,true,true).hasErrors()).isFalse();
        assertThatThrownBy(() -> importer.importDirectory(FIXTURE,MAPPING,CLUB,false,true,true)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.MIGRATION_ALREADY_APPLIED));
    }
    @Test void T_18_10_allWritesUseTargetTenantAndExistingGlobalAccountsArePreserved() {
        mongo.insert(new Document("_id","foreign").append("clubId",OTHER).append("firstName","Foreign Example").append("memberNumber",100),"members");
        var foreign=mongo.findById("foreign",Document.class,"members");
        assertThat(apply().hasErrors()).isFalse(); assertThat(mongo.findById("foreign",Document.class,"members")).isEqualTo(foreign);
        for (String collection:List.of("dogs","family_groups","memberships","migration_runs","audit_entries")) { assertThat(rows(collection)).allMatch(d -> CLUB.equals(d.get("clubId"))); }
        assertThat(TenantContext.current()).isEmpty();
    }
    @Test void T_18_01_unknownAndInvalidRowsReportErrorsWithoutAnyApplyWrites() throws Exception {
        var directory=copyFixture(); var table=PlayoffTable.read(directory.resolve("socis.csv")); table.get(1).set(4,"Unknown");
        PlayoffTable.write(directory.resolve("socis.csv"),table);
        var before=snapshot(); var report=importer.importDirectory(directory,MAPPING,CLUB,false,false,false);
        assertThat(report.hasErrors()).isTrue(); assertThat(snapshot()).isEqualTo(before);
        Files.delete(directory.resolve("tipologia.csv")); assertThat(importer.importDirectory(directory,MAPPING,CLUB,true,false,false).hasErrors()).isTrue();
    }
    @Test void T_18_01_mappingWarningsAndMalformedReferencesDoNotGuessValues() {
        for (var changes:List.of(Map.of("birthDate","bad"),Map.of("joined",""),Map.of("number","bad"),Map.of("id",""),Map.of("status","Simpatizante"),
                Map.of("phone","bad","email","invalid","document","bad","gender","Unknown"),Map.of("hasPassport","S","passport","TEST123"),Map.of("document","","email",""))) {
            assertThat(preview(mutate(0,changes)).rows()).isNotEmpty();
        }
        var data=new LinkedHashMap<>(input().files()); var groups=new ArrayList<>(data.get("groups"));
        groups.add(new PlayoffInput.Row("groups",4,Map.of("id","missing","holderId","missing","groupId","bad")));data.put("groups",groups);
        assertThat(new MigrationReport(true,preview(new PlayoffInput(data,List.of())).rows()).hasErrors()).isTrue();
    }
    PlayoffInput mutate(int index,Map<String,String> fields) {
        var data=new LinkedHashMap<>(input().files()); var members=new ArrayList<>(data.get("members")); var row=members.get(index);
        var values=new LinkedHashMap<>(row.values());values.putAll(fields);members.set(index,new PlayoffInput.Row(row.file(),row.row(),values));data.put("members",members);
        return new PlayoffInput(data,List.of());
    }
    Path copyFixture() throws Exception { var out=Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));for(var file:MAPPING.files().values()){Files.copy(FIXTURE.resolve(file.name()),out.resolve(file.name()));}return out; }
    Map<String,List<Document>> snapshot() { var result=new TreeMap<String,List<Document>>();for(String collection:mongo.getCollectionNames()){result.put(collection,rows(collection));}return result; }
}

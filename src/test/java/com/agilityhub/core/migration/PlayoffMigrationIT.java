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
    @Autowired com.agilityhub.core.identity.application.AccountService accounts;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean MigrationBankVault vault;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.agilityhub.core.clubs.messaging.application.EmailSender mail;
    @TempDir Path temp;
    @BeforeEach void seed() {
        TenantContext.clear(); clock.setInstant(Instant.parse("2026-09-09T10:00:00Z"));
        for (String collection:List.of("members","dogs","family_groups","accounts","memberships","clubs","parameters","levels","plans","prices","migration_runs",
                "migration_write_locks","census_write_locks","catalog_write_locks","audit_entries","domain_events","notifications","magic_link_tokens")) { mongo.remove(new Query(),collection); }
        clubs.save(PlatformFixtures.club(CLUB,CLUB+".example.test")); clubs.save(PlatformFixtures.club(OTHER,OTHER+".example.test")); configs.invalidate(CLUB); configs.invalidate(OTHER);
        // The plan and level codes of seeds/club-canic.yaml (S05 §12).
        for (String code:List.of("ABONAT","ABONAT_FAMILIAR","TERAPIA","PACK10","PACK6","COMPETICIO_1")) {
            String type=code.startsWith("PACK") ? "PACK" : "MONTHLY";
            mongo.insert(new Document("_id",code).append("clubId",CLUB).append("code",code).append("type",type)
                    .append("billingMode",code.equals("TERAPIA") ? "MAINTENANCE" : "MONTHLY_FEE").append("active",true),"plans");
            mongo.insert(new Document("_id","price-"+code).append("clubId",CLUB).append("planId",code)
                    .append("concept",type.equals("PACK") ? "PACK" : code.equals("TERAPIA") ? "MAINTENANCE_FEE" : "MONTHLY_FEE")
                    .append("validFrom","2020-01-01"),"prices");
        }
        for (String code:List.of("CAD","A","B","C","D","E","F","G","TER","PENDENT")) {
            mongo.insert(new Document("_id","level-"+code).append("clubId",CLUB).append("code",code).append("nameKeys",List.of(code.toLowerCase(Locale.ROOT))).append("active",true),"levels");
        }
    }
    PlayoffInput input() { return PlayoffInput.read(FIXTURE,MAPPING); }
    PlayoffPlanner.Plan preview(PlayoffInput input) { try(var tenant=TenantContext.open(CLUB)) { return planner.plan(input,MAPPING); } }
    MigrationReport apply() { return importer.importDirectory(FIXTURE,MAPPING,CLUB,false,false,false); }
    long incidents(MigrationReport report,String code) { return report.rows().stream().filter(r -> r.code().equals(code)).count(); }
    long warnings(MigrationReport report,String code) { return report.rows().stream().filter(r -> r.outcome().equals("WARNING") && r.code().equals(code)).count(); }
    /** The report lines of the source record with this ordinal (row = ordinal + 1, the header is row 1). */
    List<String> entries(MigrationReport report,int ordinal) {
        return report.rows().stream().filter(r -> r.file().equals("members") && r.row()==ordinal+1)
                .map(r -> r.entity()+" "+r.outcome()+" "+r.code()+(r.field().isEmpty() ? "" : " field="+r.field())).toList();
    }
    String source(int ordinal) { return input().files().get("members").get(ordinal-1).get("id"); }
    Document member(int ordinal) { return mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("externalIds.playoff").is(source(ordinal))),Document.class,"members"); }
    Document dog(int ordinal) { return mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("externalIds.playoff").is(source(ordinal))),Document.class,"dogs"); }
    PlayoffInput persons(List<Map<String,String>> rows) {
        var data=new LinkedHashMap<>(input().files()); var persons=new ArrayList<PlayoffInput.Row>();
        for (int i=0;i<rows.size();i++) { persons.add(new PlayoffInput.Row("persons",i+2,rows.get(i))); }
        data.put("persons",persons); return new PlayoffInput(data,List.of());
    }
    List<Document> rows(String collection) { return mongo.find(new Query(),Document.class,collection); }
    @Test @com.agilityhub.core.support.AuditCovers(com.agilityhub.core.platform.application.audit.AuditAction.MIGRATION_APPLIED)
    void T_18_01_fixtureIncidentsSplitPeopleAndDogsWithoutSignupRejections() {
        var report=apply(); assertThat(report.hasErrors()).as(report.render()).isFalse();
        assertThat(report.count("members","CREATED")).isEqualTo(187); assertThat(report.count("dogs","CREATED")).isEqualTo(189);
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
        assertThat(((List<Document>)licenseDog.get("licenses")).getFirst()).containsEntry("category","L").containsEntry("grade","2").doesNotContainKey("division");
        assertThat(((List<Document>)licenseDog.get("licenses")).get(1)).containsEntry("division","1D").doesNotContainKeys("category","grade");
        assertThat(licenseDog.get("levelId")).isEqualTo("level-A");
        assertThat(dogs.stream().filter(d -> d.get("levelId")==null)).isNotEmpty();
        assertThat(rows("audit_entries")).hasSize(1).allMatch(d -> "MigrationRun".equals(d.get("entityType")) && "MIGRATION_APPLIED".equals(d.get("action")));
        assertThat(rows("audit_entries").getFirst().getList("changes", Document.class))
                .anyMatch(change -> "details.counters.membersCREATED".equals(change.get("path")) && Long.valueOf(187).equals(change.get("after")));
    }
    @Test void T_18_02_activeNumbersWinOldLeaversAreSkippedAndNumbersReserved() {
        var report=apply(); assertThat(report.hasErrors()).isFalse(); assertThat(incidents(report,"NUMBER_CONFLICT")).isEqualTo(4);
        for (int i=185;i<=188;i++) { assertThat(member(i).get("memberNumber")).isNull(); assertThat(member(i)).containsEntry("status","LEFT").containsEntry("leftReason","MIGRATED"); }
        assertThat(member(190)).isNull(); assertThat(member(191)).isNull();
        int max=input().files().get("members").stream().mapToInt(r -> Integer.parseInt(r.get("number"))).max().orElseThrow();
        assertThat(mongo.findById(CLUB,Document.class,"clubs").get("nextMemberNumber")).isEqualTo((long)max+1);
        var modified=mutate(0,Map.of("status","Bloqueado")); var plan=preview(modified);
        assertThat((Map<String,Object>)plan.changes().stream().filter(c -> c.entity().equals("members") && c.source().row()==2).findFirst().orElseThrow().fields().get("bookingBlock")).containsValue(true);
    }
    @Test void T_18_04_explicitPayerGroupsEncryptedBankAndNewCutoverMandates() throws Exception {
        var report=apply(); assertThat(report.hasErrors()).isFalse(); assertThat(report.count("familyGroups","CREATED")).isEqualTo(1);
        var group=rows("family_groups").getFirst(); assertThat(group.get("holderMemberId")).isEqualTo(member(61).get("_id"));
        assertThat(member(81).get("familyGroupId")).isEqualTo(group.get("_id"));
        var bank=(Document)member(50).get("paymentMethod"); assertThat(bank).containsKeys("ibanEncrypted","ibanLast4","mandateRef","mandateSignedAt").doesNotContainKey("iban");
        assertThat(bank.getString("mandateRef")).isEqualTo(CLUB+"-"+member(50).get("memberNumber")+"-1");
        assertThat(bank.getDate("mandateSignedAt").toInstant()).isEqualTo(Instant.parse("2026-09-08T22:00:00Z"));
        byte[] encrypted=Base64.getDecoder().decode(bank.getString("ibanEncrypted"));
        var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(BANK_KEY),"AES"),
                new javax.crypto.spec.GCMParameterSpec(128,Arrays.copyOfRange(encrypted,0,12)));
        cipher.updateAAD((CLUB+":"+member(50).get("_id")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(new String(cipher.doFinal(Arrays.copyOfRange(encrypted,12,encrypted.length)),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(input().files().get("members").get(49).get("iban"));
        assertThat((Map<String,Object>)member(30).get("paymentMethod")).doesNotContainKey("ibanEncrypted");
        assertThat(incidents(report,"IBAN_INVALID")).isEqualTo(1); assertThat(incidents(report,"CARD_NOT_MIGRATED")).isEqualTo(1);
        assertThat((Map<String,Object>)member(38).get("paymentMethod")).containsValue("MANUAL");
    }
    @Test void T_18_06_sharedEmailsHaveOneOwnerAndNoMailIsSent() {
        var report=apply(); assertThat(report.hasErrors()).isFalse();
        assertThat(member(61).get("accountId")).isNotNull(); assertThat(member(81).get("accountId")).isNull();
        for (int i=1;i<=7;i++) { assertThat(member(i).get("accountId")).isNull(); }
        assertThat(rows("accounts")).allMatch(d -> Boolean.TRUE.equals(d.get("onboardingPending")) && "MIGRATION".equals(d.get("createdSource")) && d.get("passwordHash")==null);
        var membership=mongo.findOne(Query.query(Criteria.where("memberId").is(member(43).get("_id"))),Document.class,"memberships");
        assertThat(membership.getList("roles",String.class)).contains("MEMBER","INSTRUCTOR","ADMIN");
        assertThat((Map<String,Object>)((Map<String,Object>)member(43).get("consents")).get("privacyPolicy")).containsValue("LEGACY");
        assertThat(rows("notifications")).isEmpty(); assertThat(rows("magic_link_tokens")).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(mail);
        // 20 shared addresses in the fixture: one pair is joined through persones.csv, so 19 records are left without account.
        assertThat(warnings(report,"EMAIL_SHARED")).isEqualTo(19);
        // 7 without email + 19 EMAIL_SHARED + the 5 migrated LEFT records (R-18-12: accounts only for ACTIVE).
        assertThat(report.count("accounts","SKIPPED")).isEqualTo(31);
        assertThat(report.count("accounts","CREATED")).isEqualTo(rows("accounts").size());
        // Every EMAIL_SHARED record proposes a family group, except the pair 61/81 that family_groups.csv already groups.
        assertThat(report.count("familyGroups","PROPOSED")).isEqualTo(18);
        assertThat(report.render()).contains("familyGroups: created=1 updated=0 skipped=0 errors=0 proposed=18").contains("EMAIL_SHARED=19")
                .doesNotContain("@example","Surname","Example","ibanEncrypted");
    }
    @Test void T_18_06_E32_samePersonFamilyAndActiveLeftPairsGetTheRightPersonsDogsAndAccounts() {
        var report=apply(); assertThat(report.hasErrors()).as(report.render()).isFalse();
        // (1) Same person confirmed in persones.csv (62 principal, 82 joined): one member with two dogs and the NIF of the file.
        assertThat(member(82).get("_id")).isEqualTo(member(62).get("_id"));
        assertThat(((Document)member(62).get("idDocument")).getString("number")).isEqualTo("45128376F");
        assertThat(((Document)member(62).get("externalIds")).getList("playoff",String.class)).containsExactly(source(62),source(82));
        assertThat(mongo.count(Query.query(Criteria.where("memberId").is(member(62).get("_id"))),"dogs")).isEqualTo(2);
        assertThat(member(62).get("accountId")).isNotNull();
        assertThat(entries(report,82)).contains("members WARNING PERSON_MERGED","members SKIPPED ","dogs CREATED ")
                .doesNotContain("members WARNING EMAIL_SHARED","accounts SKIPPED ","accounts CREATED ");
        assertThat(warnings(report,"PERSON_MERGED")).isEqualTo(1);
        // (2) Same person, not confirmed (63/83), and (3) a family (64/84): two persons, the oldest «Data alta» owns the account.
        for (int[] pair:new int[][]{{63,83},{64,84}}) {
            assertThat(member(pair[1]).get("_id")).isNotEqualTo(member(pair[0]).get("_id"));
            assertThat(member(pair[0]).get("accountId")).isNotNull(); assertThat(member(pair[1]).get("accountId")).isNull();
            assertThat(entries(report,pair[1])).contains("members WARNING EMAIL_SHARED","accounts SKIPPED ","familyGroups PROPOSED EMAIL_SHARED field=holder@"+(pair[0]+1));
            assertThat((List<Document>)member(pair[1]).get("contactEmails")).extracting(d -> d.getString("email")).contains(input().files().get("members").get(pair[1]-1).get("email").toLowerCase(Locale.ROOT));
        }
        // Tie on «Data alta» (67/87): the lowest member number owns the account.
        assertThat(member(87).get("accountId")).isNotNull(); assertThat(member(67).get("accountId")).isNull();
        assertThat(entries(report,67)).contains("familyGroups PROPOSED EMAIL_SHARED field=holder@88");
        // (4) ACTIVE/LEFT pair (55/186): the LEFT record joined earlier, but only ACTIVE members get an account; no EMAIL_SHARED.
        assertThat(member(55).get("accountId")).isNotNull(); assertThat(member(186)).containsEntry("status","LEFT"); assertThat(member(186).get("accountId")).isNull();
        assertThat((List<Document>)member(186).get("contactEmails")).extracting(d -> d.getString("email")).contains(input().files().get("members").get(54).get("email").toLowerCase(Locale.ROOT));
        assertThat(entries(report,186)).contains("accounts SKIPPED ").doesNotContain("members WARNING EMAIL_SHARED");
        for (int i=185;i<=189;i++) { assertThat(member(i).get("accountId")).isNull(); }
        assertThat(mongo.count(Query.query(Criteria.where("memberId").in(List.of(member(185).get("_id"),member(186).get("_id")))),"memberships")).isZero();
    }
    @Test void T_18_06_E32_withoutThePersonsFileEachNifIsOnePerson() {
        // On an empty club, as before E32. After a load with the file, removing it is R-18-14 (T_18_08_R_18_14_personsJoinRemoved…).
        var data=new LinkedHashMap<>(input().files()); data.put("persons",List.of());
        var plan=preview(new PlayoffInput(data,List.of()));
        assertThat(plan.changes().stream().filter(c -> c.entity().equals("members")).map(PlayoffPlanner.Change::id)).hasSize(188).doesNotHaveDuplicates();
        assertThat(plan.rows()).noneMatch(r -> r.code().equals("PERSON_MERGED") || r.outcome().equals("ERROR"));
    }
    @Test void T_18_06_E32_invalidPersonsRowsAreErrorsAndNeverGuessAPerson() {
        String p62=source(62), p82=source(82), p83=source(83), p1=source(1), p190=source(190);
        for (var bad:List.of(Map.of("principalId","missing","joinedId",p82,"document","45128376F"),Map.of("principalId",p62,"joinedId",p62,"document","45128376F"),
                Map.of("principalId",p62,"joinedId",p82,"document"," "))) {
            var plan=preview(persons(List.of(bad)));
            assertThat(plan.rows()).anyMatch(r -> r.entity().equals("persons") && r.outcome().equals("ERROR") && r.code().equals("INPUT_SCHEMA_MISMATCH"));
        }
        // A chain (82 is a principal and a joined record), a second joining of 82, and a different NIF for the same principal: one error each.
        var joined=Map.of("principalId",p62,"joinedId",p82,"document","45128376F");
        for (var pair:List.of(List.of(joined,Map.of("principalId",p82,"joinedId",p83,"document","45128376F")),List.of(joined,Map.of("principalId",p1,"joinedId",p82,"document","45128376F")),
                List.of(joined,Map.of("principalId",p62,"joinedId",p83,"document","60764207R")))) {
            assertThat(preview(persons(pair)).rows().stream().filter(r -> r.entity().equals("persons") && r.outcome().equals("ERROR"))).hasSize(1);
        }
        // The principal of 190 is not migrated (an old leaver): the joined record is an error, never a person of its own.
        var plan=preview(persons(List.of(Map.of("principalId",p190,"joinedId",p83,"document","45128376F"))));
        assertThat(plan.rows()).anyMatch(r -> r.row()==84 && r.entity().equals("members") && r.outcome().equals("ERROR"));
        assertThat(plan.changes()).noneMatch(c -> c.entity().equals("members") && ((List<?>)((Map<?,?>)c.fields().get("externalIds")).get("playoff")).contains(p83));
    }
    @Test void T_18_01_mappingV2AppliesJoseAnswersB29ToB32() {
        var report=apply(); assertThat(report.hasErrors()).isFalse();
        // B31: «Familiar Abonat/curs» is ABONAT_FAMILIAR with the family behaviour (no family_groups.csv row → review warning).
        assertThat(member(32).get("planId")).isEqualTo("ABONAT_FAMILIAR");
        assertThat(entries(report,32)).contains("members WARNING MAPPING_INVALID").doesNotContain("members WARNING PLAN_UNMAPPED");
        // B30: «Quota reduïda» stays without plan.
        assertThat(member(31).get("planId")).isNull(); assertThat(entries(report,31)).contains("members WARNING PLAN_UNMAPPED");
        // B32: «Pendent» → level PENDENT with LEVEL_PENDING; «Cadells» → the seed code CAD.
        assertThat(dog(33).get("levelId")).isEqualTo("level-PENDENT"); assertThat(entries(report,33)).contains("members WARNING LEVEL_PENDING");
        assertThat(dog(48).get("levelId")).isEqualTo("level-CAD");
        // B29: the photo belongs to the dog; its source reference waits for the cutover download, no warning.
        assertThat((Map<String,Object>)dog(34).get("sourceIds")).containsEntry("playoffPhoto","[redacted]");
        assertThat(dog(34).get("photoFileKey")).isNull(); assertThat(entries(report,34)).doesNotContain("members WARNING MAPPING_INVALID");
        assertThat(mongo.findOne(new Query(),Document.class,"migration_runs").get("mappingVersion")).isEqualTo(MappingConfig.VERSION);
    }
    @Test void T_18_01_B34_instructorsGetNoPlanNorWarningAndCompetitionGetsCompeticio1() {
        var report=apply(); assertThat(report.hasErrors()).as(report.render()).isFalse();
        // «Competició 1 gos» (records 47, 51, 52) → COMPETICIO_1 with its current MONTHLY_FEE price.
        for (int ordinal:new int[]{47,51,52}) {
            assertThat(member(ordinal)).containsEntry("planId","COMPETICIO_1").containsEntry("priceId","price-COMPETICIO_1").containsEntry("nextInvoiceDate","2026-10-01");
            assertThat(entries(report,ordinal)).noneMatch(e -> e.contains("PLAN_UNMAPPED") || e.contains("LEGACY_PLAN") || e.contains("PRICE_NOT_FOUND"));
        }
        // «Instructors» (records 41–44) is not migrated as a plan: no plan, no warning, and the INSTRUCTOR role.
        for (int ordinal=41;ordinal<=44;ordinal++) {
            assertThat(member(ordinal).get("planId")).isNull(); assertThat(member(ordinal).get("priceId")).isNull();
            assertThat(entries(report,ordinal)).noneMatch(e -> e.contains("PLAN_UNMAPPED") || e.contains("LEGACY_PLAN") || e.contains("PRICE_NOT_FOUND"));
            var membership=mongo.findOne(Query.query(Criteria.where("memberId").is(member(ordinal).get("_id"))),Document.class,"memberships");
            assertThat(membership.getList("roles",String.class)).contains("MEMBER","INSTRUCTOR");
        }
        // «Quota reduïda» (B30) is still the only typology that is unmapped on purpose.
        assertThat(entries(report,31)).contains("members WARNING PLAN_UNMAPPED");
    }
    @Test void T_18_08_reapplyUpdatesOnlyMappedRecordsAndDryRunWritesNothing() {
        var before=snapshot(); var dry=importer.importDirectory(FIXTURE,MAPPING,CLUB,true,false,false);
        assertThat(dry.hasErrors()).isFalse(); assertThat(snapshot()).isEqualTo(before);
        assertThat(apply().hasErrors()).isFalse();
        mongo.insert(new Document("_id","manual").append("clubId",CLUB).append("firstName","Manual Example").append("status","ACTIVE"),"members");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(member(50).get("_id"))),new Update().set("remarks","Keep manual remarks"),"members");
        var again=apply(); assertThat(again.hasErrors()).as(again.render()).isFalse();
        assertThat(again.count("members","CREATED")).isZero(); assertThat(again.count("dogs","CREATED")).isZero(); assertThat(again.count("familyGroups","CREATED")).isZero();
        assertThat(again.count("members","UPDATED")).isEqualTo(187); assertThat(member(50).get("remarks")).isEqualTo("Keep manual remarks");
        assertThat(mongo.findById("manual",Document.class,"members")).containsEntry("firstName","Manual Example").doesNotContainKey("sourceIds");
        assertThatThrownBy(() -> importer.importDirectory(FIXTURE,MAPPING,CLUB,false,true,false)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION));
        assertThat(importer.importDirectory(FIXTURE,MAPPING,CLUB,false,true,true).hasErrors()).isFalse();
        assertThatThrownBy(() -> importer.importDirectory(FIXTURE,MAPPING,CLUB,false,true,true)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.MIGRATION_ALREADY_APPLIED));
    }
    @Test void T_18_08_R_18_14_activeRecordWithAccountThatComesBackLeftBlocksTheApply() throws Exception {
        var directory=copyFixture(); var first=importer.importDirectory(directory,MAPPING,CLUB,false,false,false); assertThat(first.hasErrors()).as(first.render()).isFalse();
        // 63 owns the account of the pair 63/83; the ACTIVE counterpart 83 shares its email.
        assertThat(member(63).get("accountId")).isNotNull();
        assertThat(mongo.findOne(Query.query(Criteria.where("memberId").is(member(63).get("_id"))),Document.class,"memberships")).isNotNull();
        setLeft(directory,63);
        var before=stored();
        for (boolean dryRun:List.of(true,false)) {
            var report=importer.importDirectory(directory,MAPPING,CLUB,dryRun,false,false);
            assertThat(entries(report,63)).containsExactly("members ERROR REEXECUTION_UNSUPPORTED field=status");
            assertThat(entries(report,83)).contains("members WARNING EMAIL_SHARED","accounts SKIPPED ").doesNotContain("accounts CREATED ","accounts UPDATED ");
            // Nothing is written: not the record, its dog, its account or its membership, and not the rest of the load.
            assertBlocked(report,1); assertThat(stored()).isEqualTo(before);
        }
        assertThat(rows("migration_runs")).hasSize(1);
    }
    @Test void T_18_08_R_18_14_personsFileAfterAFirstLoadBlocksTheApplyWithoutAnAliasConflict() throws Exception {
        var directory=copyFixture(); Files.delete(directory.resolve(MAPPING.files().get("persons").name()));
        var first=importer.importDirectory(directory,MAPPING,CLUB,false,false,false); assertThat(first.hasErrors()).as(first.render()).isFalse();
        var own=member(82); assertThat(own.get("_id")).isNotEqualTo(member(62).get("_id"));
        // Now with persones.csv (62 principal, 82 joined): the dry run and the apply report the error, and nothing is written.
        var before=stored();
        for (boolean dryRun:List.of(true,false)) {
            var report=importer.importDirectory(FIXTURE,MAPPING,CLUB,dryRun,false,false);
            assertThat(entries(report,82)).containsExactly("members ERROR REEXECUTION_UNSUPPORTED field=persons");
            assertThat(report.rows()).noneMatch(r -> Set.of("PERSON_MERGED","ID_DOCUMENT_ALREADY_EXISTS").contains(r.code()));
            assertBlocked(report,1); assertThat(stored()).isEqualTo(before);
        }
        assertThat(rows("migration_runs")).hasSize(1);
        // The confirmed NIF is the joined record's own: the same error, never ID_DOCUMENT_ALREADY_EXISTS on the principal.
        String ownDocument=((Document)own.get("idDocument")).getString("number");
        var plan=preview(persons(List.of(Map.of("principalId",source(62),"joinedId",source(82),"document",ownDocument))));
        assertBlocked(new MigrationReport(true,plan.rows()),1);
        assertThat(plan.rows()).anyMatch(r -> r.row()==83 && r.code().equals("REEXECUTION_UNSUPPORTED") && r.field().equals("persons"));
        assertThat(plan.changes()).noneMatch(c -> c.source().row()==83);
    }
    @Test void T_18_08_R_18_14_personsJoinRemovedOrChangedAfterALoadBlocksTheApply() throws Exception {
        var first=apply(); assertThat(first.hasErrors()).as(first.render()).isFalse();
        var principal=member(62).get("_id"); assertThat(member(82).get("_id")).isEqualTo(principal); assertThat(member(62).get("accountId")).isNotNull();
        var before=stored();
        // Without its join, 82 resolves through its alias to the principal's member: two persons on one member id.
        var removed=copyFixture(); Files.delete(removed.resolve(MAPPING.files().get("persons").name()));
        assertNothingPlannedFor(preview(PlayoffInput.read(removed,MAPPING)),principal);
        for (boolean dryRun:List.of(true,false)) {
            var report=importer.importDirectory(removed,MAPPING,CLUB,dryRun,false,false);
            for (int ordinal:new int[]{62,82}) { assertThat(entries(report,ordinal)).containsExactly("members ERROR REEXECUTION_UNSUPPORTED field=persons"); }
            assertBlocked(report,2); assertThat(stored()).isEqualTo(before);
        }
        // Changing the join (62 now joins 83, which the first load imported as its own person): 83 is (b), and 62/82 collide as above.
        var changed=copyFixture(); var persons=PlayoffTable.read(changed.resolve("persones.csv"));
        var cells=new ArrayList<>(persons.get(1)); cells.set(1,source(83)); persons.set(1,cells); PlayoffTable.write(changed.resolve("persones.csv"),persons);
        var plan=preview(PlayoffInput.read(changed,MAPPING)); assertNothingPlannedFor(plan,principal); assertNothingPlannedFor(plan,member(83).get("_id"));
        for (boolean dryRun:List.of(true,false)) {
            var report=importer.importDirectory(changed,MAPPING,CLUB,dryRun,false,false);
            for (int ordinal:new int[]{62,82,83}) { assertThat(entries(report,ordinal)).containsExactly("members ERROR REEXECUTION_UNSUPPORTED field=persons"); }
            assertBlocked(report,3); assertThat(stored()).isEqualTo(before);
        }
        assertThat(rows("migration_runs")).hasSize(1);
    }
    @Test void T_18_08_R_18_14_joinThatDisappearsWithItsRecordAbsentOrSkippedBlocksTheApply() throws Exception {
        // First load: 82 is a recent leaver joined to 62 (persones.csv), so its dog is INACTIVE and (a) does not apply to it.
        var loaded=copyFixture(); setLeft(loaded,82);
        var first=importer.importDirectory(loaded,MAPPING,CLUB,false,false,false); assertThat(first.hasErrors()).as(first.render()).isFalse();
        var principal=member(62).get("_id"); assertThat(member(82).get("_id")).isEqualTo(principal); assertThat(dog(82)).containsEntry("status","INACTIVE");
        var before=stored();
        // Codex #1: persones.csv removed and 82 absent from every file. Only 62 is in the input, and its stored alias 82 is gone.
        var absent=copyOf(loaded); Files.delete(absent.resolve(MAPPING.files().get("persons").name()));
        for (String file:List.of("members","plans","levels")) {
            var path=absent.resolve(MAPPING.files().get(file).name()); var table=PlayoffTable.read(path);
            table.removeIf(cells -> cells.getFirst().equals(source(82))); PlayoffTable.write(path,table);
        }
        assertNothingPlannedFor(preview(PlayoffInput.read(absent,MAPPING)),principal);
        for (boolean dryRun:List.of(true,false)) {
            var report=importer.importDirectory(absent,MAPPING,CLUB,dryRun,false,false);
            assertThat(entries(report,62)).containsExactly("members ERROR REEXECUTION_UNSUPPORTED field=persons");
            assertBlocked(report,1); assertThat(stored()).isEqualTo(before);
        }
        // The same with 82 present but skipped as an old leaver (Baixa before migration.leftMaxYears).
        var skipped=copyOf(loaded); Files.delete(skipped.resolve(MAPPING.files().get("persons").name())); setLeft(skipped,82,"2015-01-01");
        assertNothingPlannedFor(preview(PlayoffInput.read(skipped,MAPPING)),principal);
        for (boolean dryRun:List.of(true,false)) {
            var report=importer.importDirectory(skipped,MAPPING,CLUB,dryRun,false,false);
            for (int ordinal:new int[]{62,82}) { assertThat(entries(report,ordinal)).containsExactly("members ERROR REEXECUTION_UNSUPPORTED field=persons"); }
            assertBlocked(report,2); assertThat(stored()).isEqualTo(before);
        }
        assertThat(rows("migration_runs")).hasSize(1);
    }
    @Test void T_18_08_R_18_14_principalAndFamilyHolderLeftBlockTheApplyWithTheirDependants() throws Exception {
        var directory=copyFixture(); var first=importer.importDirectory(directory,MAPPING,CLUB,false,false,false); assertThat(first.hasErrors()).as(first.render()).isFalse();
        Object principal=member(62).get("_id"), holder=member(61).get("_id");
        assertThat(rows("family_groups").getFirst().get("holderMemberId")).isEqualTo(holder); assertThat(member(61).get("accountId")).isNotNull();
        setLeft(directory,61,62);
        var plan=preview(PlayoffInput.read(directory,MAPPING)); assertNothingPlannedFor(plan,principal); assertNothingPlannedFor(plan,holder);
        assertThat(plan.changes()).noneMatch(c -> c.entity().equals("family_groups"));
        var before=stored();
        for (boolean dryRun:List.of(true,false)) {
            var report=importer.importDirectory(directory,MAPPING,CLUB,dryRun,false,false);
            // The joined record of a protected principal and the family group of a protected holder are listed too.
            assertThat(entries(report,62)).containsExactly("members ERROR REEXECUTION_UNSUPPORTED field=status");
            assertThat(entries(report,82)).containsExactly("members ERROR REEXECUTION_UNSUPPORTED field=persons");
            assertThat(entries(report,61)).containsExactly("members ERROR REEXECUTION_UNSUPPORTED field=status");
            assertThat(groupErrors(report)).containsExactly("groups:2 ERROR REEXECUTION_UNSUPPORTED familyGroup");
            assertBlocked(report,4); assertThat(stored()).isEqualTo(before);
        }
        assertThat(rows("migration_runs")).hasSize(1);
    }
    @Test void T_18_08_R_18_14_storedGroupOfAProtectedHolderIsNeverReplaced() throws Exception {
        var directory=copyFixture(); var first=importer.importDirectory(directory,MAPPING,CLUB,false,false,false); assertThat(first.hasErrors()).as(first.render()).isFalse();
        var group=rows("family_groups").getFirst(); assertThat(group.get("holderMemberId")).isEqualTo(member(61).get("_id"));
        // Codex #2: holder 61 comes back LEFT, and the incoming group no longer names it (81 and 83, holder 81).
        setLeft(directory,61);
        var groups=directory.resolve(MAPPING.files().get("groups").name()); var table=PlayoffTable.read(groups); String groupId=table.get(1).getFirst();
        PlayoffTable.write(groups,List.of(table.getFirst(),List.of(groupId,source(81),source(81)),List.of(groupId,source(83),source(81))));
        var plan=preview(PlayoffInput.read(directory,MAPPING)); assertNothingPlannedFor(plan,member(61).get("_id"));
        assertThat(plan.changes()).noneMatch(c -> c.entity().equals("family_groups"));
        var before=stored();
        for (boolean dryRun:List.of(true,false)) {
            var report=importer.importDirectory(directory,MAPPING,CLUB,dryRun,false,false);
            assertThat(entries(report,61)).containsExactly("members ERROR REEXECUTION_UNSUPPORTED field=status");
            assertThat(groupErrors(report)).containsExactly("groups:2 ERROR REEXECUTION_UNSUPPORTED familyGroup");
            assertBlocked(report,2); assertThat(stored()).isEqualTo(before);
        }
        assertThat(mongo.findById(group.get("_id"),Document.class,"family_groups")).isEqualTo(group);
        assertThat(rows("migration_runs")).hasSize(1);
    }
    @Test void T_18_08_R_18_14_sameNifAliasOfAnAccountHolderThatComesBackLeftBlocksTheApply() throws Exception {
        // Records 1 and 184 share a NIF (one person, two dogs); record 1, the older one, gets an email and so owns the account.
        var directory=copyFixture(); var table=PlayoffTable.read(directory.resolve("socis.csv"));
        var cells=new ArrayList<>(table.get(1)); cells.set(22,"same-person-owner@example.test"); table.set(1,cells); PlayoffTable.write(directory.resolve("socis.csv"),table);
        var first=importer.importDirectory(directory,MAPPING,CLUB,false,false,false); assertThat(first.hasErrors()).as(first.render()).isFalse();
        var person=member(1).get("_id"); assertThat(member(184).get("_id")).isEqualTo(person); assertThat(member(1).get("accountId")).isNotNull();
        setLeft(directory,1);
        // The ACTIVE alias 184 resolves to the protected member and plans nothing for it.
        assertNothingPlannedFor(preview(PlayoffInput.read(directory,MAPPING)),person);
        var before=stored();
        for (boolean dryRun:List.of(true,false)) {
            var report=importer.importDirectory(directory,MAPPING,CLUB,dryRun,false,false);
            assertThat(entries(report,1)).containsExactly("members ERROR REEXECUTION_UNSUPPORTED field=status");
            assertThat(entries(report,184)).containsExactly("members ERROR REEXECUTION_UNSUPPORTED field=status");
            assertBlocked(report,2); assertThat(stored()).isEqualTo(before);
        }
        assertThat(rows("migration_runs")).hasSize(1);
    }
    /** R-18-14 (amended 24-09): any REEXECUTION_UNSUPPORTED blocks the whole apply, and the report says so (R-18-15). */
    static void assertBlocked(MigrationReport report,long unsupported) {
        assertThat(report.hasErrors()).isTrue();
        assertThat(report.rows()).as(report.render()).filteredOn(r -> r.outcome().equals("ERROR")).allMatch(r -> r.code().equals(MigrationReport.REEXECUTION_UNSUPPORTED));
        assertThat(report.unsupported()).as(report.render()).isEqualTo(unsupported);
        assertThat(report.render()).contains("Validation failed; no changes applied","REEXECUTION_UNSUPPORTED: "+unsupported+" records cannot be reconciled","--reset")
                .doesNotContain("the rest","left untouched");
    }
    static List<String> groupErrors(MigrationReport report) {
        return report.rows().stream().filter(r -> r.entity().equals("familyGroups") && !r.outcome().equals("PROPOSED")).map(r -> r.file()+":"+r.row()+" "+r.outcome()+" "+r.code()+" "+r.field()).toList();
    }
    /** Everything stored, except the outbox rows, whose dispatch status a background job may change; their number is kept. */
    Map<String,Object> stored() {
        var result=new TreeMap<String,Object>(snapshot()); result.put("domain_events",mongo.count(new Query(),"domain_events")); return result;
    }
    /** The records with these ordinals become recent leavers (`Baixa`, 2026-06-30). */
    void setLeft(Path directory,int... ordinals) throws Exception { for (int ordinal:ordinals) { setLeft(directory,ordinal,"2026-06-30"); } }
    void setLeft(Path directory,int ordinal,String date) throws Exception {
        var table=PlayoffTable.read(directory.resolve("socis.csv")); var cells=new ArrayList<>(table.get(ordinal)); cells.set(4,"Baixa"); cells.set(26,date); table.set(ordinal,cells);
        PlayoffTable.write(directory.resolve("socis.csv"),table);
    }
    /** R-18-14: a protected member gets no member, dog or identity change, and no member id is planned twice. */
    static void assertNothingPlannedFor(PlayoffPlanner.Plan plan,Object memberId) {
        assertThat(plan.changes()).noneMatch(c -> c.entity().equals("members") && c.id().equals(memberId))
                .noneMatch(c -> c.entity().equals("dogs") && memberId.equals(c.fields().get("memberId")));
        assertThat(plan.identities()).noneMatch(i -> i.memberId().equals(memberId));
        assertThat(plan.changes().stream().filter(c -> c.entity().equals("members")).map(PlayoffPlanner.Change::id)).doesNotHaveDuplicates();
    }
    @Test void T_18_10_allWritesUseTargetTenantAndExistingGlobalAccountsArePreserved() {
        String email=input().files().get("members").get(49).get("email");
        var account=accounts.getOrCreate(email,"Existing Example","en",com.agilityhub.core.identity.persistence.Account.Source.SIGNUP,null,false);
        var existing=mongo.findById(account.id(),Document.class,"accounts");
        mongo.insert(new Document("_id","foreign").append("clubId",OTHER).append("firstName","Foreign Example").append("memberNumber",100),"members");
        var foreign=mongo.findById("foreign",Document.class,"members");
        assertThat(apply().hasErrors()).isFalse(); assertThat(mongo.findById("foreign",Document.class,"members")).isEqualTo(foreign);
        assertThat(mongo.findById(account.id(),Document.class,"accounts")).isEqualTo(existing);
        assertThat(member(50).get("accountId")).isEqualTo(account.id());
        for (String collection:List.of("dogs","family_groups","memberships","migration_runs","audit_entries")) { assertThat(rows(collection)).allMatch(d -> CLUB.equals(d.get("clubId"))); }
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_18_08_failedCensusRollsBackRecordsAuditAndAccountEventsAndCanBeRetried() {
        org.mockito.Mockito.doThrow(new IllegalStateException("Synthetic encryption failure")).when(vault).encrypt(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        assertThatThrownBy(this::apply).isInstanceOf(IllegalStateException.class);
        for(String collection:List.of("members","dogs","family_groups","memberships","accounts","audit_entries")) { assertThat(rows(collection)).isEmpty(); }
        assertThat(rows("migration_runs")).hasSize(1).allMatch(d -> "FAILED".equals(d.get("status")));
        assertThat(rows("domain_events")).hasSize(1).allMatch(d -> "MigrationRunFailed".equals(d.get("type")));
        org.mockito.Mockito.reset(vault);
        assertThat(apply().hasErrors()).isFalse();
        assertThat(rows("migration_runs")).hasSize(2);
    }
    @Test void T_18_01_unknownAndInvalidRowsReportErrorsWithoutAnyApplyWrites() throws Exception {
        var directory=copyFixture(); var table=PlayoffTable.read(directory.resolve("socis.csv")); table.set(1,new ArrayList<>(table.get(1))); table.get(1).set(4,"Unknown");
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
    Path copyFixture() throws Exception { return copyOf(FIXTURE); }
    Path copyOf(Path directory) throws Exception {
        var out=Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
        for (var file:MAPPING.files().values()) { if (Files.exists(directory.resolve(file.name()))) { Files.copy(directory.resolve(file.name()),out.resolve(file.name())); } }
        return out;
    }
    Map<String,List<Document>> snapshot() { var result=new TreeMap<String,List<Document>>();for(String collection:mongo.getCollectionNames()){result.put(collection,rows(collection));}return result; }
}

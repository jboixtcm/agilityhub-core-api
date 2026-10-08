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
    Path fixture;
    static final MappingConfig MAPPING=MappingConfig.load(null);
    static final String CLUB="playoff-test",OTHER="playoff-other";
    static final String BANK_KEY=Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
    // Ruling E85: only BILLING_BANK_KEY is set; the migration and S12's vault share it (no MIGRATION_BANK_KEY any more).
    @DynamicPropertySource static void bank(DynamicPropertyRegistry registry) { registry.add("core.billing.bank-key",() -> BANK_KEY); }
    @Autowired PlayoffImportService importer; @Autowired PlayoffPlanner planner; @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs; @Autowired ClubConfigService configs;
    @Autowired com.agilityhub.core.identity.application.AccountService accounts;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean MigrationBankVault vault;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.agilityhub.core.clubs.messaging.application.EmailSender mail;
    @TempDir Path temp;
    @BeforeEach void seed() throws Exception {
        fixture = copyOf(Path.of("src/test/resources/fixtures/playoff"));
        TenantContext.clear(); clock.setInstant(Instant.parse("2026-09-09T10:00:00Z"));
        for (String collection:List.of("members","dogs","family_groups","accounts","memberships","clubs","parameters","levels","plans","prices","migration_runs",
                "leave_requests","invoices","pack_balances","collections","remittances","upload_grants","migration_reset_guards","tenant_write_counters","migration_write_locks","census_write_locks","catalog_write_locks","audit_entries","domain_events","notifications","magic_link_tokens")) { mongo.remove(new Query(),collection); }
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
    PlayoffInput input() { return PlayoffInput.read(fixture,MAPPING); }
    PlayoffPlanner.Plan preview(PlayoffInput input) { try(var tenant=TenantContext.open(CLUB)) { return planner.plan(input,MAPPING); } }
    MigrationReport apply() { return importer.importDirectory(fixture,MAPPING,CLUB,false,false,false); }
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
    @org.springframework.beans.factory.annotation.Autowired com.agilityhub.core.payments.application.BankAccountVault bankAccounts;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.core.env.Environment environment;
    /**
     * Ruling E85 (clarifies E43, round 2 of E8-T01): with only `BILLING_BANK_KEY` configured, `migration:apply` imports the IBANs
     * and S12's {@code BankAccountVault} — the SEPA writer's only way to a full IBAN — reads every one of them back; Core no
     * longer reads `MIGRATION_BANK_KEY`.
     */
    @SuppressWarnings("unchecked") @Test void T_18_04_withOnlyTheBillingBankKeyTheSepaVaultReadsBackEveryImportedIban() throws Exception {
        var report=apply(); assertThat(report.hasErrors()).isFalse();
        int read=0; var members=input().files().get("members");
        for (int ordinal=1; ordinal<=members.size(); ordinal++) {
            var stored=member(ordinal); if (stored==null) { continue; }
            var bank=(Map<String,Object>)stored.get("paymentMethod");
            if (bank==null || !bank.containsKey("ibanEncrypted")) { continue; }
            String iban=bankAccounts.resolve(bank,CLUB,String.valueOf(stored.get("_id")));
            assertThat(iban.replace(" ","").toUpperCase()).as("member "+ordinal).isEqualTo(members.get(ordinal-1).get("iban").replace(" ","").toUpperCase());
            read++;
        }
        assertThat(read).as("imported IBANs read back").isPositive();
        assertThat(environment.getProperty("core.migration.bank-key")).as("no MIGRATION_BANK_KEY binding").isNull();
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
        var before=snapshot(); var dry=importer.importDirectory(fixture,MAPPING,CLUB,true,false,false);
        assertThat(dry.hasErrors()).isFalse(); assertThat(snapshot()).isEqualTo(before);
        assertThat(apply().hasErrors()).isFalse();
        mongo.insert(new Document("_id","manual").append("clubId",CLUB).append("firstName","Manual Example").append("status","ACTIVE"),"members");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(member(50).get("_id"))),new Update().set("remarks","Keep manual remarks"),"members");
        var again=apply(); assertThat(again.hasErrors()).as(again.render()).isFalse();
        assertThat(again.count("members","CREATED")).isZero(); assertThat(again.count("dogs","CREATED")).isZero(); assertThat(again.count("familyGroups","CREATED")).isZero();
        assertThat(again.count("members","UPDATED")).isEqualTo(187); assertThat(member(50).get("remarks")).isEqualTo("Keep manual remarks");
        assertThat(mongo.findById("manual",Document.class,"members")).containsEntry("firstName","Manual Example").doesNotContainKey("sourceIds");
        assertThatThrownBy(() -> importer.importDirectory(fixture,MAPPING,CLUB,false,true,false)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION));
        assertThat(importer.importDirectory(fixture,MAPPING,CLUB,false,true,true).hasErrors()).isFalse();
        assertThatThrownBy(() -> importer.importDirectory(fixture,MAPPING,CLUB,false,true,true)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.MIGRATION_ALREADY_APPLIED));
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
            var report=importer.importDirectory(fixture,MAPPING,CLUB,dryRun,false,false);
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
    Path copyFixture() throws Exception { return copyOf(fixture); }
    Path copyOf(Path directory) throws Exception {
        var out=Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
        for (var file:MAPPING.files().values()) { if (Files.exists(directory.resolve(file.name()))) { Files.copy(directory.resolve(file.name()),out.resolve(file.name())); } }
        return out;
    }
    Map<String,List<Document>> snapshot() { var result=new TreeMap<String,List<Document>>();for(String collection:mongo.getCollectionNames()){result.put(collection,rows(collection));}return result; }
    @Test void T_18_05_T_18_15_historicalReceiptsAndPackOpeningAreIdempotentAndNeverCollected() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().addToSet("modules","PACKS"), "clubs"); configs.invalidate(CLUB);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("PACK10")), new Update().set("pack", Map.of("sessions",10,"validityMonths",6))
                .set("dogsIncluded",1).set("showOnSignup",false).set("showOnWeb",false).set("order",0).set("version",0L), "plans");
        var receipts = new StringBuilder("Receipt ID;Member ID;Number;Date;Concept;Total;Status;Method\n");
        for (int index=1; index<=103; index++) {
            receipts.append("receipt-").append(index).append(';').append(source(1)).append(';').append(index)
                .append(";2026-02-15;Febrer 2026 fictional fee;30,50;").append(index==1 ? "Impagado" : index==2 ? "Pendiente" : index==3 ? "Vencido" : "Pagado").append(";SEPA_DD\n");
        }
        receipts.append("old;").append(source(1)).append(";999;2023-01-01;old;1,00;Pagado;MANUAL\n");
        Files.writeString(fixture.resolve("rebuts.csv"),receipts);
        Files.writeString(fixture.resolve("packs.csv"),"Pack ID;Member ID;Plan;Opened on;Consumed\nactive;"+source(1)+";PACK10;2026-08-01;6\nexpired;"+source(1)+";PACK10;2026-01-01;2\n");
        var dry=importer.importDirectory(fixture,MAPPING,CLUB,true,false,false,LocalDate.of(2026,9,1));
        assertThat(dry.hasErrors()).as(dry.render()).isFalse(); assertThat(rows("invoices")).isEmpty(); assertThat(rows("members")).isEmpty();
        var report=importer.importDirectory(fixture,MAPPING,CLUB,false,false,false,LocalDate.of(2026,9,1));
        assertThat(report.hasErrors()).as(report.render()).isFalse();
        assertThat(report.count("invoices","CREATED")).isEqualTo(103); assertThat(report.count("packBalances","CREATED")).isEqualTo(2);
        assertThat(report.totals()).containsEntry("invoicesPAID",100L).containsEntry("invoicesPENDING",2L).containsEntry("invoicesFAILED",1L)
                .containsEntry("packsACTIVE",1L).containsEntry("packsEXPIRED",1L);
        assertThat(rows("invoices")).allSatisfy(invoice -> {
            assertThat(invoice).containsEntry("kind","MIGRATED").containsEntry("series","PLAYOFF").containsEntry("period","2026-02");
            assertThat(invoice.getList("lines",Document.class)).singleElement().satisfies(line -> assertThat(line).containsEntry("origin","MIGRATED"));
            assertThat(invoice.get("remittanceId")).isNull();
        });
        assertThat(rows("pack_balances")).allSatisfy(pack -> {
            assertThat(pack.getList("movements",Document.class)).singleElement().satisfies(movement -> assertThat(movement).containsEntry("type","OPEN").containsEntry("reason","MIGRATED"));
            assertThat(pack.getInteger("remaining")+pack.getInteger("consumed")).isEqualTo(10);
        });
        assertThat(warnings(report, "PACK_DOG_AMBIGUOUS")).isEqualTo(2);
        for (var pack : rows("pack_balances")) {
            boolean active = "active".equals(pack.get("sourceIds", Document.class).getString("playoffPackId"));
            assertThat(pack).containsEntry("state", active ? "ACTIVE" : "EXPIRED")
                    .containsEntry("expiresOn", active ? "2027-01-31" : "2026-06-30")
                    .containsEntry("remaining", active ? 4 : 8).containsEntry("consumed", active ? 6 : 2)
                    .containsEntry("dogId", dog(1).getString("_id"));
        }
        for (var invoice : rows("invoices")) {
            long number = ((Number) invoice.get("number")).longValue();
            assertThat(invoice.getString("displayNumber")).isEqualTo(Long.toString(number));
            assertThat(invoice.getString("status")).isEqualTo(number == 1 ? "FAILED" : number <= 3 ? "PENDING" : "PAID");
            assertThat(invoice.getList("lines", Document.class).getFirst()).containsEntry("description", "Febrer 2026 fictional fee");
        }
        assertThat(rows("collections")).isEmpty(); assertThat(rows("remittances")).isEmpty();
        var again=importer.importDirectory(fixture,MAPPING,CLUB,false,false,false,LocalDate.of(2026,9,1));
        assertThat(again.count("invoices","CREATED")).isZero(); assertThat(again.count("packBalances","CREATED")).isZero();
        assertThat(rows("invoices")).hasSize(103); assertThat(rows("pack_balances")).hasSize(2);
        try (var tenant = TenantContext.open(CLUB)) {
            var fresh = transactions.execute(status -> invoiceActions.createManual(member(1).getString("_id"),
                    List.of(new com.agilityhub.core.payments.application.InvoiceActions.ManualLine("Fictional adjustment", new Money(1000, "EUR"), java.math.BigDecimal.ZERO)), false, null));
            assertThat(fresh.invoice().series()).isEqualTo("2026"); assertThat(fresh.invoice().number()).isEqualTo(1);
            assertThat(fresh.invoice().displayNumber()).isEqualTo("2026-0001");
        }
        assertThat(rows("invoices")).hasSize(104);
        // R-18-15: ids and codes only (`holder@88` is E2-T10's row reference, not an address).
        assertThat(report.render()).doesNotContainPattern("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\\.[A-Za-z]{2,}|ES[0-9]{22}|[0-9]{8}[A-Z]");
    }
    @Test void T_18_05_receiptsForSkippedMembersAndCollidingNumbersWarnWithoutBlockingTheLoad() throws Exception {
        Files.writeString(fixture.resolve("rebuts.csv"), "Receipt ID;Member ID;Number;Date;Concept;Total;Status;Method\n"
                + "kept;" + source(1) + ";A-1;2026-08-01;Fictional fee;10,00;Pagado;MANUAL\n"
                + "collision;" + source(1) + ";B-1;2026-08-01;Fictional fee;10,00;Pagado;MANUAL\n"
                + "skipped-member;" + source(190) + ";2;2026-08-01;Fictional fee;10,00;Pagado;MANUAL\n");
        var report = apply();
        assertThat(report.hasErrors()).as(report.render()).isFalse();
        assertThat(report.count("members", "CREATED")).isEqualTo(187);
        assertThat(report.count("invoices", "CREATED")).isEqualTo(1);
        assertThat(report.count("invoices", "SKIPPED")).isEqualTo(2);
        assertThat(report.rows()).filteredOn(r -> r.entity().equals("invoices") && r.outcome().equals("WARNING"))
                .extracting(MigrationReport.Entry::code).containsExactly("NUMBER_CONFLICT", "MAPPING_INVALID");
        assertThat(rows("invoices")).singleElement().satisfies(i -> assertThat(i.getString("displayNumber")).isEqualTo("A-1"));
        Files.writeString(fixture.resolve("rebuts.csv"), "Receipt ID;Member ID;Number;Date;Concept;Total;Status;Method\n"
                + "later-collision;" + source(1) + ";C-1;2026-08-01;Fictional fee;10,00;Pagado;MANUAL\n");
        var later = apply(); assertThat(later.hasErrors()).as(later.render()).isFalse();
        assertThat(later.rows()).filteredOn(r -> r.entity().equals("invoices") && r.code().equals("NUMBER_CONFLICT")).hasSize(1);
        assertThat(later.count("invoices", "SKIPPED")).isEqualTo(1);
        assertThat(rows("invoices")).hasSize(1);
    }
    @Test void T_18_15_futureLeaveImportsAnApprovedMigratedRequestOnceWithoutNotifications() throws Exception {
        var table = PlayoffTable.read(fixture.resolve("socis.csv"));
        int left = table.getFirst().indexOf("Data baixa"); assertThat(left).isNotNegative();
        table.set(1, new ArrayList<>(table.get(1))); table.get(1).set(left, "2026-12-31");
        PlayoffTable.write(fixture.resolve("socis.csv"), table);
        var report = apply(); assertThat(report.hasErrors()).as(report.render()).isFalse();
        var member = member(1); assertThat(member).containsEntry("status", "ACTIVE").containsEntry("leaveDate", "2026-12-31");
        var request = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("memberId").is(member.getString("_id"))), Document.class, "leave_requests");
        assertThat(request).isNotNull();
        assertThat(request).containsEntry("source", "MIGRATED").containsEntry("state", "APPROVED");
        assertThat(request.get("decision", Document.class)).containsEntry("effectiveDate", "2026-12-31");
        assertThat(member.getString("leaveRequestId")).isEqualTo(request.getString("_id"));
        apply();
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "leave_requests")).containsExactly(request);
        assertThat(rows("notifications")).isEmpty();
    }
    @Test void T_18_03_point1_crashAfterCommittedBillingBatchCanRestartWithoutDuplicates() throws Exception {
        var receipts = new StringBuilder("Receipt ID;Member ID;Number;Date;Concept;Total;Status;Method\n");
        String sourceId = source(1);
        for (int i = 1; i <= 103; i++) {
            receipts.append("resume-").append(i).append(';').append(sourceId).append(';').append(i)
                    .append(";2026-08-01;Fictional fee;10,00;Pagado;MANUAL\n");
        }
        Files.writeString(fixture.resolve("rebuts.csv"), receipts);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            if (calls.incrementAndGet() == 101) { throw new AssertionError("Simulated process interruption"); }
            return call.callRealMethod();
        }).when(billing).receipt(org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> importer.importDirectory(fixture, MAPPING, CLUB, false, true, true))
                .isInstanceOf(AssertionError.class).hasMessage("Simulated process interruption");
        var committed = rows("invoices"); assertThat(committed).hasSize(100);
        assertThat(rows("migration_runs")).singleElement().satisfies(r -> assertThat(r.getString("status")).isEqualTo("RUNNING"));
        long members = rows("members").size(), accountsBefore = rows("accounts").size();
        org.mockito.Mockito.reset(billing);
        clock.setInstant(clock.instant().plusSeconds(1800));
        var resumed = importer.importDirectory(fixture, MAPPING, CLUB, false, true, true);
        assertThat(resumed.hasErrors()).as(resumed.render()).isFalse();
        assertThat(rows("invoices")).hasSize(103).containsAll(committed);
        assertThat(rows("members")).hasSize((int) members); assertThat(rows("accounts")).hasSize((int) accountsBefore);
        assertThat(rows("migration_runs")).extracting(r -> r.getString("status")).containsExactlyInAnyOrder("FAILED", "COMPLETED");
        assertThatThrownBy(() -> importer.importDirectory(fixture, MAPPING, CLUB, false, true, true))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.MIGRATION_ALREADY_APPLIED));
    }

    @Autowired com.agilityhub.core.migration.persistence.MigrationRunRepository migrationRuns;

    @Test void T_18_03_point1_liveOwnerAndExpiredOwnerAreFencedPerTenant() {
        try (var tenant = TenantContext.open(CLUB)) {
            migrationRuns.acquire("first-worker", clock.instant());
            assertThatThrownBy(() -> migrationRuns.acquire("second-worker", clock.instant()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.MIGRATION_ALREADY_APPLIED));
        }
        try (var other = TenantContext.open(OTHER)) {
            migrationRuns.acquire("other-worker", clock.instant());
            transactions.executeWithoutResult(status -> migrationRuns.fence("other-worker", clock.instant()));
        }
        try (var tenant = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> migrationRuns.lockReset(clock.instant())))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.MIGRATION_ALREADY_APPLIED));
            clock.setInstant(clock.instant().plusSeconds(601));
            migrationRuns.acquire("second-worker", clock.instant());
            boolean stillOwned = transactions.execute(status -> migrationRuns.tryFence("first-worker", clock.instant()));
            assertThat(stillOwned).isFalse();
            migrationRuns.release("first-worker");
            transactions.executeWithoutResult(status -> migrationRuns.fence("second-worker", clock.instant()));
            migrationRuns.release("second-worker");
            transactions.executeWithoutResult(status -> migrationRuns.lockReset(clock.instant()));
        }
    }

    @Test void T_18_03_point1_expiryDuringBatchRollsBackAndCannotMarkTheRunFailed() throws Exception {
        String sourceId = source(1);
        Files.writeString(fixture.resolve("rebuts.csv"), "Receipt ID;Member ID;Number;Date;Concept;Total;Status;Method\n"
                + "expired-worker;" + sourceId + ";1;2026-08-01;Fictional fee;10,00;Pagado;MANUAL\n");
        org.mockito.Mockito.doAnswer(call -> {
            Object result = call.callRealMethod(); clock.setInstant(clock.instant().plusSeconds(601)); return result;
        }).when(billing).receipt(org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> importer.importDirectory(fixture, MAPPING, CLUB, false, true, true))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.MIGRATION_ALREADY_APPLIED));
        assertThat(rows("invoices")).isEmpty();
        assertThat(rows("migration_runs")).singleElement().satisfies(r -> assertThat(r.getString("status")).isEqualTo("RUNNING"));
        assertThat(rows("domain_events")).noneMatch(e -> "MigrationRunFailed".equals(e.getString("type")));
        org.mockito.Mockito.reset(billing);
        assertThat(importer.importDirectory(fixture, MAPPING, CLUB, false, true, true).hasErrors()).isFalse();
        assertThat(rows("invoices")).hasSize(1);
    }

    void futureLeave(String date) throws Exception {
        var table = PlayoffTable.read(fixture.resolve("socis.csv"));
        table.set(1, new ArrayList<>(table.get(1))); table.get(1).set(table.getFirst().indexOf("Data baixa"), date);
        PlayoffTable.write(fixture.resolve("socis.csv"), table);
    }

    @Test void T_18_15_point3_changedFutureLeaveKeepsMemberAndDecisionTogether() throws Exception {
        futureLeave("2026-12-31"); assertThat(apply().hasErrors()).isFalse();
        String requestId = member(1).getString("leaveRequestId");
        futureLeave("2027-01-31"); assertThat(apply().hasErrors()).isFalse();
        assertThat(member(1)).containsEntry("leaveDate", "2027-01-31").containsEntry("leaveRequestId", requestId);
        var request = mongo.findById(requestId, Document.class, "leave_requests");
        assertThat(request.get("decision", Document.class)).containsEntry("effectiveDate", "2027-01-31");
        assertThat(request).containsEntry("requestedDate", "2027-01-31").containsEntry("state", "APPROVED");
        apply(); assertThat(rows("leave_requests")).containsExactly(request);
        assertThat(rows("notifications")).isEmpty();
    }

    @Test void T_18_15_point3_removedFutureLeaveIsRejectedBeforeAnyWrite() throws Exception {
        futureLeave("2026-12-31"); assertThat(apply().hasErrors()).isFalse();
        futureLeave(""); var before = snapshot(); var report = apply();
        assertThat(report.hasErrors()).isTrue();
        assertThat(report.rows()).anyMatch(r -> r.code().equals(MigrationReport.REEXECUTION_UNSUPPORTED) && r.field().equals("leaveDate"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.agilityhub.core.migration.persistence.MigrationResetRepository resetData;

    @Test void T_18_16_point2_sameMillisecondWriteRefusesReset() {
        assertThat(importer.importDirectory(fixture, MAPPING, CLUB, false, true, true).hasErrors()).isFalse();
        try (var tenant = TenantContext.open(CLUB)) {
            var member = member(1);
            memberService.patch(member.getString("_id"), Map.of("version", member.get("version"), "remarks", "Fictional later edit"), false);
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> resetData.requireNoLaterWrites()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.MIGRATION_ALREADY_APPLIED));
        }
        assertThat(member(1)).containsEntry("remarks", "Fictional later edit");
    }

    Path resetSeed() throws Exception {
        var lines = Files.readString(Path.of("seeds/club-canic.yaml"));
        var pagesDir = Files.createDirectories(temp.resolve("reset-seeds/pages"));
        try (var pages = Files.list(Path.of("seeds/pages"))) {
            for (Path page : pages.toList()) { Files.copy(page, pagesDir.resolve(page.getFileName())); }
        }
        return Files.writeString(pagesDir.getParent().resolve("club-canic.yaml"),
                lines.substring(0, lines.indexOf("\naccounts:") + 1) + lines.substring(lines.indexOf("\ncatalogs:") + 1));
    }

    @Test void T_18_16_point2_writeBetweenGuardAndDeletionCannotBeSilentlyLost() throws Exception {
        Path seed = resetSeed(); String club = definitions.apply(seed, false).id();
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        var writerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try {
            assertThat(importer.importDirectory(fixture, MAPPING, club, false, true, true).hasErrors()).isFalse();
            String edited = mongo.findOne(Query.query(Criteria.where("clubId").is(club).and("status").is("ACTIVE")), Document.class, "members").getString("_id");
            org.mockito.Mockito.doAnswer(call -> {
                call.callRealMethod();
                var independent = new org.springframework.transaction.support.TransactionTemplate(transactions.getTransactionManager());
                independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                try {
                    independent.executeWithoutResult(status -> {
                        var member = mongo.findById(edited, Document.class, "members");
                        memberService.patch(edited, Map.of("version", member.get("version"), "remarks", "Concurrent fictional edit"), false);
                    });
                    committed.set(true);
                } catch (org.springframework.dao.DataAccessException conflict) { writerFailure.set(conflict); }
                return null;
            }).when(resetData).requireNoLaterWrites();
            Throwable resetFailure = catchThrowable(() -> reset.reset("canic", seed, "canic", "canic"));
            if (committed.get()) {
                assertThat(resetFailure).as("A committed write must refuse reset").isNotNull();
                assertThat(mongo.findById(edited, Document.class, "members")).containsEntry("remarks", "Concurrent fictional edit");
            } else {
                assertThat(writerFailure.get()).as("The writer must explicitly fail if reset wins the shared lock").isNotNull();
                assertThat(resetFailure).isNull();
                assertThat(mongo.count(Query.query(Criteria.where("clubId").is(club)), "members")).isZero();
            }
        } finally {
            org.mockito.Mockito.reset(resetData);
            for (String collection : mongo.getCollectionNames()) {
                if (!collection.startsWith("system.")) { mongo.remove(Query.query(Criteria.where("clubId").is(club)), collection); }
            }
            mongo.remove(Query.query(Criteria.where("_id").is(club)), "clubs");
        }
    }

    @Test void T_18_04_malformedExportedMandateDatesProduceSafeReportRows() throws Exception {
        for (String date : List.of("", "not-a-date")) {
            Files.writeString(fixture.resolve("mandats.csv"), "Member ID;Mandate reference;Signed on\n" + source(50) + ";legacy-reference;" + date + "\n");
            var report = importer.importDirectory(fixture, MAPPING, CLUB, true, false, false);
            assertThat(report.hasErrors()).isTrue();
            assertThat(report.rows()).anySatisfy(r -> {
                assertThat(r.file()).isEqualTo("mandates"); assertThat(r.code()).isEqualTo("INPUT_SCHEMA_MISMATCH");
            });
            assertThat(rows("members")).isEmpty();
        }
    }
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.agilityhub.core.payments.application.BillingMigrationAccess billing;
    @Autowired MigrationResetService reset;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired com.agilityhub.core.payments.application.InvoiceActions invoiceActions;
    @Autowired com.agilityhub.core.platform.application.definition.ClubDefinitions definitions;
    @Autowired com.agilityhub.core.clubs.census.application.MemberService memberService;
    /** The Cànic's plan kinds with fictional prices and a SEPA creditor, so that S12's simulation bills the migrated census. */
    void billingCatalog() {
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "plans"); mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "prices");
        var at = Instant.parse("2026-01-01T00:00:00Z");
        for (String code : List.of("ABONAT", "ABONAT_FAMILIAR", "TERAPIA", "PACK10", "PACK6", "COMPETICIO_1")) {
            boolean pack = code.startsWith("PACK"), therapy = code.equals("TERAPIA");
            var type = pack ? com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PlanType.PACK : com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PlanType.MONTHLY;
            mongo.insert(new com.agilityhub.core.clubs.catalogs.persistence.Plan(code, CLUB, code, new LocalizedText(Map.of("ca", code), "ca"), type,
                    therapy ? com.agilityhub.core.clubs.catalogs.domain.OfferTerms.BillingMode.MAINTENANCE : com.agilityhub.core.clubs.catalogs.domain.OfferTerms.BillingMode.MONTHLY_FEE,
                    code.equals("ABONAT_FAMILIAR") ? 2 : 1, null, pack ? new com.agilityhub.core.clubs.catalogs.domain.OfferTerms.Pack(10, 6) : null, null, null, null,
                    true, true, 1, true, 0L, at, at, null, null));
            var concept = pack ? com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept.PACK : therapy
                    ? com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept.MAINTENANCE_FEE : com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept.MONTHLY_FEE;
            mongo.insert(new com.agilityhub.core.clubs.catalogs.persistence.Price("price-" + code, CLUB, code, concept, new Money(therapy ? 3000 : pack ? 12000 : 6000, "EUR"),
                    java.math.BigDecimal.ZERO, LocalDate.of(2020, 1, 1), null, 0L, at, at, null, null));
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().set("updatedAt", at)
                .set("paymentProviders.SEPA_XML", Map.of("enabled", true, "creditorName", "Club Agility Exemple", "creditorId", "ES00ZZZB00000000", "iban", "ES0000000000000000009876"))
                .set("paymentProviders.MANUAL", Map.of("enabled", true)), "clubs");
        configs.invalidate(CLUB);
    }
    static String euros(long minor) { return (minor / 100) + "," + String.format("%02d", minor % 100); }
    Document lastRun() { return mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)).with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "startedAt", "finishedAt")).limit(1), Document.class, "migration_runs"); }

    /**
     * T-18-07 (R-18-13, R-18-15): after the load, S12's simulation of M+1 is compared with Playoff's «Informe de previsión» (a
     * fictional file). A forecast that matches the members the new system bills → `RECONCILED`; one that also bills the direct
     * debits with no usable bank account → beyond 1 %: `COMPLETED`, and one `NO_BANK_ACCOUNT` justification line per member that
     * explains the difference. The report carries ids and codes only (negative grep of email, tax id and IBAN patterns).
     */
    @Test void T_18_07_theNextMonthsSimulationReconcilesWithTheForecastWithinOnePercent() throws Exception {
        billingCatalog(); var cutover = LocalDate.of(2026, 9, 1);
        assertThat(importer.importDirectory(fixture, MAPPING, CLUB, false, false, false, cutover).hasErrors()).isFalse();
        com.agilityhub.core.payments.application.BillingMigrationAccess.Reconciliation simulated;
        try (var tenant = TenantContext.open(CLUB)) { simulated = billing.reconcile(YearMonth.of(2026, 10)); }
        assertThat(simulated.totalMinor()).isPositive();
        var sources = new HashMap<String, String>();
        rows("members").forEach(m -> sources.put(m.getString("_id"), ((Document) m.get("sourceIds")).getString("playoffMemberId")));
        var matching = new StringBuilder("Member ID;Period;Total\n");
        simulated.byMember().forEach((id, minor) -> matching.append(sources.get(id)).append(";2026-10;").append(euros(minor)).append('\n'));
        Files.writeString(fixture.resolve("previsio.csv"), matching);
        clock.setInstant(clock.instant().plusSeconds(60));
        var reconciled = importer.importDirectory(fixture, MAPPING, CLUB, false, false, false, cutover);
        assertThat(reconciled.hasErrors()).as(reconciled.render()).isFalse();
        assertThat(reconciled.totals()).containsEntry("reconciliationExpectedMinor", simulated.totalMinor()).containsEntry("reconciliationDifferenceMinor", 0L);
        assertThat(lastRun().getString("status")).isEqualTo("RECONCILED");
        String changedId = simulated.byMember().keySet().iterator().next();
        for (long delta : List.of(simulated.totalMinor() / 200, simulated.totalMinor() / 20)) {
            Files.writeString(fixture.resolve("previsio.csv"), matching + sources.get(changedId) + ";2026-10;" + euros(delta) + "\n");
            clock.setInstant(clock.instant().plusSeconds(60));
            var changed = importer.importDirectory(fixture, MAPPING, CLUB, false, false, false, cutover);
            boolean within = delta == simulated.totalMinor() / 200;
            assertThat(changed.totals()).containsEntry("reconciliationDifferenceMinor", -delta);
            assertThat(lastRun().getString("status")).isEqualTo(within ? "RECONCILED" : "COMPLETED");
            var differences = changed.rows().stream().filter(r -> r.entity().equals("reconciliation")).toList();
            if (within) { assertThat(differences).isEmpty(); }
            else { assertThat(differences).singleElement().satisfies(r -> {
                assertThat(r.code()).isEqualTo("MAPPING_INVALID"); assertThat(r.field()).isEqualTo(changedId);
            }); }
        }
        var unbanked = simulated.incidents().entrySet().stream().filter(e -> e.getValue().equals("NO_BANK_ACCOUNT")).map(Map.Entry::getKey).toList();
        assertThat(unbanked).hasSizeGreaterThanOrEqualTo(26); // the 26 without an IBAN, plus the ones whose IBAN failed mod-97
        var playoff = new StringBuilder(matching);
        unbanked.forEach(id -> playoff.append(sources.get(id)).append(";2026-10;60,00\n"));
        Files.writeString(fixture.resolve("previsio.csv"), playoff);
        clock.setInstant(clock.instant().plusSeconds(60));
        var explained = importer.importDirectory(fixture, MAPPING, CLUB, false, false, false, cutover);
        assertThat(explained.totals()).containsEntry("reconciliationDifferenceMinor", -6000L * unbanked.size());
        assertThat(lastRun().getString("status")).isEqualTo("COMPLETED");
        assertThat(explained.rows()).filteredOn(r -> r.entity().equals("reconciliation"))
                .extracting(MigrationReport.Entry::field, MigrationReport.Entry::code)
                .containsExactlyInAnyOrderElementsOf(unbanked.stream().map(id -> tuple(id, "NO_BANK_ACCOUNT")).toList());
        for (var row : input().files().get("members")) {
            for (String field : List.of("firstName", "surname", "email", "document", "iban")) {
                if (!row.get(field).isBlank()) { assertThat(explained.render()).doesNotContain(row.get(field)); }
            }
        }
        assertThat(explained.render()).doesNotContainPattern("\\b[XYZ][0-9]{7}[A-Z]\\b|\\bES(?:[ \\t]*[0-9]){22}\\b");
        assertThat(explained.render()).doesNotContainPattern("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\\.[A-Za-z]{2,}|ES[0-9]{22}|\\b[0-9]{8}[A-Z]\\b");
    }

    /**
     * T-18-16 (R-18-14): on the Cànic's seed, `--reset` (the slug typed twice) erases the load and applies the seed again, and a
     * fresh load gives the same report. After an `--apply --env production` it is allowed while nothing was written after the
     * load; one later write (a member edit) → refused. A wrong second slug → refused before anything is touched.
     */
    @Test void T_18_16_resetErasesTheLoadAndAFreshLoadGivesTheSameReport() throws Exception {
        // The Cànic's seed without its staff accounts (their passwords need SEED_PASSWORD, which the ITs do not set).
        var lines = Files.readString(Path.of("seeds/club-canic.yaml"));
        var seeds = Files.createDirectories(temp.resolve("seeds/pages"));
        try (var pages = Files.list(Path.of("seeds/pages"))) { for (Path page : pages.toList()) { Files.copy(page, seeds.resolve(page.getFileName())); } }
        Path seed = Files.writeString(seeds.getParent().resolve("club-canic.yaml"), lines.substring(0, lines.indexOf("\naccounts:") + 1) + lines.substring(lines.indexOf("\ncatalogs:") + 1));
        var cutover = LocalDate.of(2026, 9, 1);
        String canic = definitions.apply(seed, false).id();
        try {
            var first = importer.importDirectory(fixture, MAPPING, canic, false, false, false, cutover);
            assertThat(first.hasErrors()).as(first.render()).isFalse();
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(canic)), "members")).isEqualTo(187);
            long plans = mongo.count(Query.query(Criteria.where("clubId").is(canic)), "plans");
            assertThatThrownBy(() -> reset.reset("canic", seed, "canic", "canix")).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION));
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(canic)), "members")).isEqualTo(187);
            assertThat(reset.reset("canic", seed, "canic", "canic")).isEqualTo(canic);
            assertThat(mongo.find(Query.query(Criteria.where("clubId").is(canic).and("action").is("MIGRATION_APPLIED").and("reason").is("RESET")), Document.class, "audit_entries"))
                    .singleElement().satisfies(a -> assertThat(a.getString("entityId")).isEqualTo(canic));
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(canic)), "members")).isZero();
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(canic)), "migration_runs")).isZero();
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(canic)), "plans")).isEqualTo(plans);
            var again = importer.importDirectory(fixture, MAPPING, canic, false, false, false, cutover);
            // Same report, except that AgilityHub ID accounts are global (not club data): they survive the reset and are UPDATED.
            assertThat(again.count("accounts", "UPDATED")).isEqualTo(first.count("accounts", "CREATED"));
            assertThat(again.rows().stream().map(r -> r.entity().equals("accounts") && r.outcome().equals("UPDATED")
                    ? new MigrationReport.Entry(r.file(), r.row(), r.entity(), "CREATED", r.code(), r.field()) : r).toList()).isEqualTo(first.rows());
            assertThat(again.totals()).isEqualTo(first.totals());
            // Production: a load with nothing written after it can still be reset.
            reset.reset("canic", seed, "canic", "canic");
            assertThat(importer.importDirectory(fixture, MAPPING, canic, false, true, true, cutover).hasErrors()).isFalse();
            clock.setInstant(clock.instant().plusSeconds(60));
            reset.reset("canic", seed, "canic", "canic");
            assertThat(importer.importDirectory(fixture, MAPPING, canic, false, true, true, cutover).hasErrors()).isFalse();
            clock.setInstant(clock.instant().plusSeconds(60));
            String edited = mongo.findOne(Query.query(Criteria.where("clubId").is(canic).and("status").is("ACTIVE")), Document.class, "members").getString("_id");
            try (var tenant = TenantContext.open(canic)) {
                var version = mongo.findById(edited, Document.class, "members").get("version");
                memberService.patch(edited, Map.of("version", version, "remarks", "Edited after the load"), false);
            }
            assertThatThrownBy(() -> reset.reset("canic", seed, "canic", "canic")).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.MIGRATION_ALREADY_APPLIED));
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(canic)), "members")).isEqualTo(187);
        } finally {
            for (String collection : mongo.getCollectionNames()) {
                if (!collection.startsWith("system.")) { mongo.remove(Query.query(Criteria.where("clubId").is(canic)), collection); }
            }
            mongo.remove(Query.query(Criteria.where("_id").is(canic)), "clubs");
        }
    }
    @Autowired MigrationPhotos photos;
    @Test void T_18_05_photosUseLocalStorageAndKeepUploadedFilesWithSafePerDogFailures() throws Exception {
        apply(); var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        server.createContext("/ok",exchange -> { exchange.getResponseHeaders().set("Content-Type","image/png"); exchange.sendResponseHeaders(200,png.length); exchange.getResponseBody().write(png); exchange.close(); });
        server.createContext("/missing",exchange -> { exchange.sendResponseHeaders(404,-1); exchange.close(); });
        server.createContext("/wrong",exchange -> { exchange.getResponseHeaders().set("Content-Type","text/plain"); exchange.sendResponseHeaders(200,1); exchange.getResponseBody().write(0); exchange.close(); });
        byte[] large=new byte[9*1024*1024];
        server.createContext("/large",exchange -> { try { exchange.getResponseHeaders().set("Content-Type","image/png"); exchange.sendResponseHeaders(200,large.length); exchange.getResponseBody().write(large); } catch(java.io.IOException cancelled) { } finally { exchange.close(); } });
        server.start();
        try {
            var selected=rows("dogs").subList(0,7); String base="http://127.0.0.1:"+server.getAddress().getPort();
            for (int index=0;index<7;index++) {
                String source=index==4 ? "[redacted]" : base+List.of("/ok","/missing","/wrong","/large","/ok","/ok","/ok").get(index);
                var update=new Update().set("sourceIds.playoffPhoto",source);
                if (index==6) { update.set("status", "PENDING").set("readmissionRequest", Map.of("requestedAt", Date.from(clock.instant()))); }
                if(index==5) { update.set("photoFileKey","existing-upload"); }
                mongo.updateFirst(Query.query(Criteria.where("_id").is(selected.get(index).get("_id"))),update,"dogs");
            }
            assertThat(photos.run(CLUB,true)).filteredOn(row -> row.outcome().equals("IMPORTED")).isEmpty();
            var result=photos.run(CLUB,false);
            assertThat(result).filteredOn(row -> row.outcome().equals("IMPORTED")).hasSize(1);
            assertThat(result).filteredOn(row -> row.dogId().equals(selected.get(6).getString("_id"))).singleElement()
                    .satisfies(row -> assertThat(row.outcome()).isEqualTo("INVALID_STATE"));
            assertThat(mongo.findById(selected.get(6).getString("_id"), Document.class, "dogs").get("photoFileKey")).isNull();
            assertThat(result).extracting(MigrationPhotos.Row::outcome).contains("NOT_FOUND","FILE_TYPE_NOT_ALLOWED","FILE_TOO_LARGE","SKIPPED");
            assertThat(mongo.findById(selected.get(0).get("_id"),Document.class,"dogs").get("photoFileKey")).isNotNull();
            assertThat(mongo.findById(selected.get(5).get("_id"),Document.class,"dogs").get("photoFileKey")).isEqualTo("existing-upload");
            assertThat(photos.run(CLUB,false)).noneMatch(row -> row.outcome().equals("IMPORTED"));
            assertThat(result.toString()).doesNotContain(base,"@example.test");
        } finally { server.stop(0); }
    }

}

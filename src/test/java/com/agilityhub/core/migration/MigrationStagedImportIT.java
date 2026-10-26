package com.agilityhub.core.migration;

import com.agilityhub.core.clubs.census.application.CensusMigrationService;
import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.payments.application.BillingMigrationAccess;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.AopTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MigrationStagedImportIT extends AbstractIntegrationTest {
    static final String CLUB="staged-migration-test",OTHER="staged-migration-other";
    static final MappingConfig MAPPING=MappingConfig.load(null);
    static final LocalDate CUTOVER=LocalDate.of(2026,9,1);
    @Autowired PlayoffImportService importer;
    @Autowired PlayoffPlanner planner;
    @Autowired MongoTemplate mongo;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @MockitoSpyBean CensusMigrationService census;
    @MockitoSpyBean BillingMigrationAccess billing;
    @MockitoSpyBean MigrationApplyService apply;
    @MockitoBean MigrationReportStore reportStore;
    @TempDir Path temp;
    Path fixture;

    @BeforeEach void seed() throws Exception {
        TenantContext.clear(); clock.setInstant(Instant.parse("2026-09-09T10:00:00Z"));
        for(String collection:List.of("members","dogs","family_groups","accounts","memberships","clubs","parameters","levels","plans","prices","migration_runs",
                "saved_views","billing_simulations","leave_requests","invoices","pack_balances","collections","remittances","upload_grants","migration_reset_guards",
                "tenant_write_counters","migration_write_locks","census_write_locks","catalog_write_locks","audit_entries","domain_events","notifications","magic_link_tokens")) {
            mongo.remove(new Query(),collection);
        }
        fixture=Files.createDirectory(temp.resolve("fictional-input"));
        for(var file:MAPPING.files().values()) {
            Path original=Path.of("src/test/resources/fixtures/playoff").resolve(file.name());
            if(Files.exists(original)) { Files.copy(original,fixture.resolve(file.name())); }
        }
        clubs.save(PlatformFixtures.club(CLUB,CLUB+".example.test")); clubs.save(PlatformFixtures.club(OTHER,OTHER+".example.test"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)),new Update().addToSet("modules","PACKS"),"clubs");
        configs.invalidate(CLUB); configs.invalidate(OTHER);
        for(String code:List.of("ABONAT","ABONAT_FAMILIAR","TERAPIA","PACK10","PACK6","COMPETICIO_1")) {
            boolean pack=code.startsWith("PACK");
            var plan=new Document("_id",code).append("clubId",CLUB).append("code",code).append("type",pack ? "PACK" : "MONTHLY")
                    .append("billingMode",code.equals("TERAPIA") ? "MAINTENANCE" : "MONTHLY_FEE").append("active",true)
                    .append("dogsIncluded",1).append("showOnSignup",false).append("showOnWeb",false).append("order",0).append("version",0L);
            if(pack) { plan.append("pack",Map.of("sessions",10,"validityMonths",6)).append("dogsIncluded",1); }
            mongo.insert(plan,"plans");
            mongo.insert(new Document("_id","price-"+code).append("clubId",CLUB).append("planId",code)
                    .append("concept",pack ? "PACK" : code.equals("TERAPIA") ? "MAINTENANCE_FEE" : "MONTHLY_FEE").append("validFrom","2020-01-01"),"prices");
        }
        for(String code:List.of("CAD","A","B","C","D","E","F","G","TER","PENDENT")) {
            mongo.insert(new Document("_id","level-"+code).append("clubId",CLUB).append("code",code).append("nameKeys",List.of(code.toLowerCase(Locale.ROOT))).append("active",true),"levels");
        }
        mongo.insert(new Document("_id","other-manual-member").append("clubId",OTHER).append("firstName","Other fictional person"),"members");
    }
    PlayoffInput input() { return PlayoffInput.read(fixture,MAPPING); }
    String source(int ordinal) { return input().files().get("members").get(ordinal-1).get("id"); }
    Document member(int ordinal) { return mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("externalIds.playoff").is(source(ordinal))),Document.class,"members"); }
    List<Document> rows(String collection) { return mongo.find(Query.query(Criteria.where("clubId").is(CLUB)),Document.class,collection); }
    MigrationRun latest() { return mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)).with(Sort.by(Sort.Direction.DESC,"execution.ordinal")),MigrationRun.class); }
    MigrationExecutionRequest request(String steps,String resume) { return new MigrationExecutionRequest(MigrationSteps.parse(steps),true,resume,null); }
    MigrationReport execute(boolean dry,boolean production,MigrationExecutionRequest request) {
        return importer.importDirectory(fixture,MAPPING,CLUB,dry,production,production,CUTOVER,request);
    }
    void green(MigrationReport report) { assertThat(report.hasErrors()).as(report.render()).isFalse(); }
    void rejected(Runnable action,ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(code));
    }
    Map<String,List<Document>> business() {
        var result=new TreeMap<String,List<Document>>();
        for(String collection:List.of("members","dogs","family_groups","accounts","memberships","invoices","pack_balances","migration_runs","audit_entries","domain_events")) {
            result.put(collection,mongo.find(new Query(),Document.class,collection));
        }
        return result;
    }
    void change(int ordinal,String field,String value) throws Exception {
        var file=MAPPING.files().get("members"); var table=PlayoffTable.read(fixture.resolve(file.name()));
        int column=file.columns().stream().filter(item->item.field().equals(field)).findFirst().orElseThrow().at()-1;
        var row=new ArrayList<>(table.get(ordinal)); row.set(column,value); table.set(ordinal,row); PlayoffTable.write(fixture.resolve(file.name()),table);
    }
    void receipts(int count) throws Exception {
        var text=new StringBuilder("Receipt ID;Member ID;Number;Date;Concept;Total;Status;Method\n");
        for(int i=1;i<=count;i++) { text.append("staged-").append(i).append(';').append(source(1)).append(';').append(i)
                .append(";2026-08-01;Fictional fee;10,00;Pagado;MANUAL\n"); }
        Files.writeString(fixture.resolve("rebuts.csv"),text);
    }

    @Test void T_18_13_partialDryRunAndResumePreserveExcludedFieldsAndOtherTenant() {
        var before=business(); green(execute(true,false,request("1-3",null))); assertThat(business()).isEqualTo(before);
        green(execute(false,false,request("1-3",null))); var first=latest();
        assertThat(first.execution().completedSteps()).containsExactly(1,2,3); assertThat(first.execution().pipelineComplete()).isFalse();
        assertThat(rows("members")).hasSize(187).allSatisfy(row->assertThat(row).containsEntry("status","PENDING")
                .doesNotContainKeys("paymentMethod","planId","priceId","nextInvoiceDate","bookingBlock","accountId"));
        assertThat(rows("family_groups")).hasSize(1); assertThat(rows("dogs")).isEmpty(); assertThat(rows("invoices")).isEmpty();
        assertThat(rows("pack_balances")).isEmpty(); assertThat(mongo.count(new Query(),"accounts")).isZero();
        String id=member(1).getString("_id");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("clubId").is(CLUB)),new Update().set("firstName","Preserved fictional name").set("remarks","Keep this field"),"members");
        green(execute(false,false,request("4-10",first.id()))); var completed=latest();
        assertThat(completed.execution().pipelineComplete()).isTrue(); assertThat(completed.execution().priorRunId()).isEqualTo(first.id());
        assertThat(rows("dogs")).hasSize(189); assertThat(member(1)).containsEntry("firstName","Preserved fictional name").containsEntry("remarks","Keep this field").containsEntry("status","ACTIVE");
        assertThat(member(50)).containsKeys("paymentMethod","planId"); assertThat(mongo.count(new Query(),"accounts")).isPositive();
        assertThat(member(61).get("familyGroupId")).isEqualTo(member(81).get("familyGroupId"));
        assertThat(mongo.findById("other-manual-member",Document.class,"members")).containsExactlyInAnyOrderEntriesOf(before.get("members").getFirst());
        verifyNoInteractions(reportStore);
    }
    @Test void T_18_13_missingPersonAndDogDependenciesRefuseBeforeBusinessWrites() throws Exception {
        receipts(1); Files.writeString(fixture.resolve("packs.csv"),"Pack ID;Member ID;Plan;Opened on;Consumed\npack-1;"+source(1)+";PACK10;2026-08-01;1\n");
        var before=business();
        for(String step:List.of("4","7","8","9")) { rejected(()->execute(false,false,request(step,null)),ErrorCode.INPUT_SCHEMA_MISMATCH); assertThat(business()).isEqualTo(before); }
        green(execute(false,false,request("1-3",null))); String first=latest().id(); var afterPeople=business();
        rejected(()->execute(false,false,request("8",first)),ErrorCode.INPUT_SCHEMA_MISMATCH); assertThat(business()).isEqualTo(afterPeople);
    }
    @Test void T_18_13_disabledBillingAndPacksSkipTheirWritesWithoutSuppressingPeopleDogsOrAccounts() throws Exception {
        receipts(1); Files.writeString(fixture.resolve("packs.csv"),"Pack ID;Member ID;Plan;Opened on;Consumed\npack-1;"+source(1)+";PACK10;2026-08-01;1\n");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)),new Update().pullAll("modules",new Object[]{"BILLING","PACKS"}),"clubs");
        configs.invalidate(CLUB);
        green(execute(false,false,request("1-10",null)));
        assertThat(latest().execution().pipelineComplete()).isTrue();
        assertThat(rows("members")).hasSize(187).allSatisfy(row->assertThat(row).doesNotContainKeys("paymentMethod","planId","priceId","nextInvoiceDate"));
        assertThat(rows("dogs")).hasSize(189); assertThat(mongo.count(new Query(),"accounts")).isPositive();
        assertThat(rows("invoices")).isEmpty(); assertThat(rows("pack_balances")).isEmpty();
        verify(billing,never()).receipt(any()); verify(billing,never()).pack(any()); verify(billing,never()).reconcile(any());
    }
    @Test void T_18_13_productionResumeRequiresSameInputsCutoverAndNoInterveningApplicationWrite() throws Exception {
        green(execute(false,true,request("1-3",null))); var first=latest();
        var original=Files.readAllBytes(fixture.resolve("socis.csv"));
        change(1,"firstName","Changed source name");
        rejected(()->execute(false,true,request("4-10",first.id())),ErrorCode.INPUT_SCHEMA_MISMATCH);
        Files.write(fixture.resolve("socis.csv"),original);
        rejected(()->importer.importDirectory(fixture,MAPPING,CLUB,false,true,true,CUTOVER.plusDays(1),request("4-10",first.id())),ErrorCode.INPUT_SCHEMA_MISMATCH);
        assertThat(rows("migration_runs")).hasSize(1); assertThat(rows("dogs")).isEmpty();
        green(execute(false,true,request("4",first.id()))); var second=latest();
        assertThat(second.execution().completedSteps()).containsExactly(1,2,3,4);
        mongo.updateFirst(Query.query(Criteria.where("clubId").is(CLUB).and("_id").is(member(1).get("_id"))),new Update().set("remarks","Later fictional edit"),"members");
        rejected(()->execute(false,true,request("5-10",second.id())),ErrorCode.MIGRATION_ALREADY_APPLIED);
        assertThat(rows("migration_runs")).hasSize(2); assertThat(member(1)).containsEntry("remarks","Later fictional edit");
    }
    @Test void T_18_13_failedDogBatchKeepsPriorStagesAndResumesWithoutDuplicatePeopleOrDogs() {
        var calls=new AtomicInteger();
        doAnswer(call->{ if(calls.incrementAndGet()==101) { throw new IllegalStateException("Fictional dog batch failure"); } return call.callRealMethod(); })
                .when(census).dog(anyString(),anyMap());
        assertThatThrownBy(()->execute(false,true,request("1-10",null))).isInstanceOf(IllegalStateException.class).hasMessage("Fictional dog batch failure");
        var failed=latest(); assertThat(failed.status()).isEqualTo("FAILED"); assertThat(failed.execution().completedSteps()).containsExactly(1,2,3);
        assertThat(rows("members")).hasSize(187); assertThat(rows("dogs")).hasSize(100); assertThat(mongo.count(new Query(),"accounts")).isZero();
        reset(census); green(execute(false,true,request("4-10",failed.id())));
        assertThat(rows("members")).hasSize(187); assertThat(rows("dogs")).hasSize(189);
        assertThat(latest().execution().pipelineComplete()).isTrue(); assertThat(rows("migration_runs")).hasSize(2);
    }
    @Test void T_18_13_failedReceipt101KeepsFirst100AndResumeAddsOnlyThree() throws Exception {
        receipts(103); var calls=new AtomicInteger();
        doAnswer(call->{ if(calls.incrementAndGet()==101) { throw new IllegalStateException("Fictional receipt batch failure"); } return call.callRealMethod(); })
                .when(billing).receipt(any());
        assertThatThrownBy(()->execute(false,true,request("1-10",null))).isInstanceOf(IllegalStateException.class).hasMessage("Fictional receipt batch failure");
        var failed=latest(); assertThat(failed.status()).isEqualTo("FAILED"); assertThat(failed.execution().completedSteps()).containsExactly(1,2,3,4,5,6);
        var first100=rows("invoices"); assertThat(first100).hasSize(100); assertThat(mongo.count(new Query(),"accounts")).isZero();
        reset(billing); var resumed=execute(false,true,request("7-10",failed.id())); green(resumed);
        assertThat(rows("invoices")).hasSize(103).containsAll(first100); assertThat(resumed.count("invoices","CREATED")).isEqualTo(3);
        assertThat(latest().execution().pipelineComplete()).isTrue(); assertThat(rows("members")).hasSize(187);
    }
    @Test void T_18_13_processInterruptionLeavesRunningAttemptAndExplicitResumeRecovers() {
        var calls=new AtomicInteger();
        doAnswer(call->{ if(calls.incrementAndGet()==101) { throw new AssertionError("Fictional process interruption"); } return call.callRealMethod(); })
                .when(census).dog(anyString(),anyMap());
        assertThatThrownBy(()->execute(false,true,request("1-10",null))).isInstanceOf(AssertionError.class).hasMessage("Fictional process interruption");
        var interrupted=latest(); assertThat(interrupted.status()).isEqualTo("RUNNING");
        assertThat(interrupted.execution().completedSteps()).containsExactly(1,2,3);
        assertThat(rows("members")).hasSize(187); assertThat(rows("dogs")).hasSize(100);
        reset(census); green(execute(false,true,request("4-10",interrupted.id())));
        assertThat(rows("members")).hasSize(187); assertThat(rows("dogs")).hasSize(189);
        assertThat(latest().execution().pipelineComplete()).isTrue();
        assertThat(mongo.findById(interrupted.id(),MigrationRun.class).status()).isEqualTo("FAILED");
    }
    @Test void T_18_13_terminalCompletionFailureRollsBackLastStepAndResumesIt() {
        MigrationApplyService target=AopTestUtils.getUltimateTargetObject(apply);
        doAnswer(call->{ call.callRealMethod(); throw new IllegalStateException("Fictional completion event failure"); })
                .when(target).complete(any(),any(),anyBoolean());
        assertThatThrownBy(()->execute(false,true,request("1-10",null))).isInstanceOf(IllegalStateException.class).hasMessage("Fictional completion event failure");
        var failed=latest(); assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.execution().completedSteps()).containsExactly(1,2,3,4,5,6,7,8,9);
        var people=rows("members"); var dogs=rows("dogs"); var accounts=mongo.find(new Query(),Document.class,"accounts");
        assertThat(people).hasSize(187); assertThat(dogs).hasSize(189); assertThat(accounts).isNotEmpty();
        reset(target); green(execute(false,true,request("10",failed.id())));
        assertThat(latest().execution().pipelineComplete()).isTrue(); assertThat(latest().execution().completedThisRun()).containsExactly(1,10);
        assertThat(rows("members")).containsExactlyInAnyOrderElementsOf(people);
        assertThat(rows("dogs")).containsExactlyInAnyOrderElementsOf(dogs);
        assertThat(mongo.find(new Query(),Document.class,"accounts")).containsExactlyInAnyOrderElementsOf(accounts);
    }
    @Test void T_18_13_peopleBatchResumeRestoresBacklinksToAlreadyCompletedGroup() throws Exception {
        List<PlayoffPlanner.Change> people;
        try(var ignored=TenantContext.open(CLUB)) {
            people=planner.plan(input(),MAPPING,CUTOVER).changes().stream().filter(change->change.entity().equals("members")
                    && "ACTIVE".equals(change.fields().get("status"))).toList();
        }
        var first=people.get(130); var second=people.get(131);
        PlayoffTable.write(fixture.resolve("family_groups.csv"),List.of(List.of("ID grup","ID associat","ID titular"),
                List.of("fictional-late-group",first.source().get("id"),first.source().get("id")),
                List.of("fictional-late-group",second.source().get("id"),first.source().get("id"))));
        var calls=new AtomicInteger();
        doAnswer(call->{ if(calls.incrementAndGet()==101) { throw new IllegalStateException("Fictional person batch failure"); } return call.callRealMethod(); })
                .when(census).member(anyString(),anyMap());
        assertThatThrownBy(()->execute(false,true,request("1-10",null))).isInstanceOf(IllegalStateException.class).hasMessage("Fictional person batch failure");
        var failed=latest(); assertThat(failed.status()).isEqualTo("FAILED"); assertThat(failed.execution().completedSteps()).containsExactly(1,2);
        assertThat(rows("members")).hasSize(100); assertThat(rows("family_groups")).hasSize(1); assertThat(rows("dogs")).isEmpty();
        assertThat(mongo.findById(first.id(),Document.class,"members")).isNull();
        assertThat(mongo.findById(second.id(),Document.class,"members")).isNull();
        String groupId=rows("family_groups").getFirst().getString("_id");
        reset(census); green(execute(false,true,request("3-10",failed.id())));
        assertThat(latest().execution().pipelineComplete()).isTrue(); assertThat(rows("members")).hasSize(187); assertThat(rows("dogs")).hasSize(189);
        assertThat(rows("family_groups")).hasSize(1);
        assertThat(mongo.findById(first.id(),Document.class,"members")).containsEntry("familyGroupId",groupId);
        assertThat(mongo.findById(second.id(),Document.class,"members")).containsEntry("familyGroupId",groupId);
    }
    @Test void T_18_14_approvedReapplyUpdatesMappedFieldsButStaleApprovalAndUnsupportedTransitionsStayBlocked() throws Exception {
        green(execute(false,true,request("1-10",null))); var prior=latest();
        String original=member(1).getString("firstName"); change(1,"firstName","Approved fictional change");
        var approved=approval(prior);
        green(execute(false,true,new MigrationExecutionRequest(MigrationSteps.all(),true,null,approved)));
        assertThat(member(1)).containsEntry("firstName","Approved fictional change"); assertThat(member(1).getString("firstName")).isNotEqualTo(original);
        var current=latest(); assertThat(current.execution().approvalDigest()).isEqualTo(approved.digest()); assertThat(current.execution().priorRunId()).isEqualTo(prior.id());
        rejected(()->execute(false,true,new MigrationExecutionRequest(MigrationSteps.all(),true,null,approved)),ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION);
        change(43,"status","Baixa"); change(43,"left","2026-09-01");
        var before=business(); var forbidden=execute(false,true,new MigrationExecutionRequest(MigrationSteps.all(),true,null,approval(current)));
        assertThat(forbidden.hasErrors()).isTrue(); assertThat(forbidden.rows()).anyMatch(row->row.code().equals("REEXECUTION_UNSUPPORTED"));
        assertThat(business()).isEqualTo(before);
    }
    @Test void T_18_13_billingForANewMergedSourceAliasRequiresThePersonStep() throws Exception {
        green(execute(false,false,request("1-10",null)));
        String memberId=member(1).getString("_id"),alias="fictional-new-source-alias";
        var file=MAPPING.files().get("members"); var table=PlayoffTable.read(fixture.resolve(file.name()));
        var appended=new ArrayList<>(table.get(1));
        var replacements=Map.of("id",alias,"number","999999999","chip","","dogName","Fictional additional dog");
        file.columns().forEach(column->{ if(replacements.containsKey(column.field())) { appended.set(column.at()-1,replacements.get(column.field())); } });
        table.add(appended); PlayoffTable.write(fixture.resolve(file.name()),table);
        Files.writeString(fixture.resolve("rebuts.csv"),"Receipt ID;Member ID;Number;Date;Concept;Total;Status;Method\nalias-receipt;"
                +alias+";99999;2026-08-01;Fictional alias fee;10,00;Pagado;MANUAL\n");
        Files.writeString(fixture.resolve("packs.csv"),"Pack ID;Member ID;Plan;Opened on;Consumed\nalias-pack;"+alias+";PACK10;2026-08-01;1\n");
        try(var ignored=TenantContext.open(CLUB)) {
            var candidate=planner.plan(input(),MAPPING,CUTOVER);
            assertThat(candidate.rows()).noneMatch(row->row.outcome().equals("ERROR"));
            assertThat(candidate.changes().stream().filter(change->change.entity().equals("members") && change.id().equals(memberId)).toList())
                    .singleElement().satisfies(change->assertThat(((Map<?,?>)change.fields().get("externalIds")).get("playoff").toString()).contains(alias));
        }
        var before=business();
        for(String step:List.of("7","8")) {
            assertThatThrownBy(()->execute(false,false,request(step,null))).isInstanceOfSatisfying(ApiException.class,error->{
                assertThat(error.code()).isEqualTo(ErrorCode.INPUT_SCHEMA_MISMATCH);
                assertThat(error.details()).containsEntry("requiredStep",3);
            });
            assertThat(business()).isEqualTo(before);
        }
        green(execute(false,false,request("3-9",null)));
        assertThat(rows("members")).hasSize(187); assertThat(rows("dogs")).hasSize(190);
        assertThat(member(1).get("externalIds",Document.class).getList("playoff",String.class)).contains(alias);
        assertThat(rows("invoices")).singleElement().satisfies(row->assertThat(row).containsEntry("memberId",memberId));
        assertThat(rows("pack_balances")).singleElement().satisfies(row->assertThat(row).containsEntry("memberId",memberId));
    }
    ReapplyApproval approval(MigrationRun prior) {
        return new ReapplyApproval(CLUB,prior.id(),ReapplyApproval.manifestSha256(input().manifest()),CUTOVER,MigrationSteps.all(),
                "Fictional operator",clock.instant(),"Fictional mapped-field correction","fictional-runbook-entry",UUID.randomUUID().toString());
    }
}

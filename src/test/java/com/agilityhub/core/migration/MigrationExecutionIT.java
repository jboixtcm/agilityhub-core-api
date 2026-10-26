package com.agilityhub.core.migration;

import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class MigrationExecutionIT extends AbstractIntegrationTest {
    static final String CLUB="migration-execution-test",OTHER="migration-execution-other";
    static final LocalDate CUTOVER=LocalDate.parse("2026-10-30");
    final InputManifest manifest=new InputManifest("a".repeat(64),List.of(new InputManifest.File("members.csv","b".repeat(64),2)));
    @Autowired MigrationRunRepository runs; @Autowired MongoTemplate mongo; @Autowired TransactionTemplate transactions;
    @BeforeEach void seed() {
        TenantContext.clear(); mongo.remove(Query.query(Criteria.where("clubId").in(CLUB,OTHER)),MigrationRun.class); TenantContext.open(CLUB);
    }
    @AfterEach void clear() { TenantContext.clear(); }
    MigrationExecutionRequest request(String selection,String resume) { return new MigrationExecutionRequest(MigrationSteps.parse(selection),true,resume,null); }
    MigrationRun run(String id,String status,MigrationExecution execution) {
        return new MigrationRun(id,CLUB,"PLAYOFF","APPLY","PRODUCTION",2,status,clock.instant(),"RUNNING".equals(status) ? null : clock.instant(),
                Map.of(),manifest,List.of(),null,null,null,execution);
    }
    MigrationRun partial(String id) {
        var execution=runs.authorizeExecution(id,request("1-3",null),manifest,CUTOVER,true,clock.instant());
        for(int step:List.of(1,2,3)) { execution=execution.stepCompleted(step); }
        return runs.insert(run(id,"COMPLETED",execution));
    }
    void rejected(Runnable action,ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(code));
    }
    @Test void T_18_13_readOnlyPreviewAndResumePreserveExactLineageAndStepProgress() {
        var preview=runs.previewExecution(request("1-3",null),manifest,CUTOVER,true,clock.instant());
        assertThat(preview.selectedSteps()).containsExactly(1,2,3); assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)),MigrationRun.class)).isZero();
        partial("first");
        var next=runs.authorizeExecution("second",request("4-6","first"),manifest,CUTOVER,true,clock.instant());
        assertThat(next.rootRunId()).isEqualTo("first"); assertThat(next.priorRunId()).isEqualTo("first");
        assertThat(next.ordinal()).isEqualTo(2); assertThat(next.completedBefore()).containsExactly(1,2,3); assertThat(next.pipelineComplete()).isFalse();
        runs.insert(run("second","RUNNING",next));
        transactions.executeWithoutResult(status->{ runs.stepCompleted("second",1); runs.stepCompleted("second",4); });
        var stored=runs.findById("second").orElseThrow();
        assertThat(stored.execution().completedSteps()).containsExactly(1,2,3,4);
        var finished=stored.finished("FAILED",clock.instant(),Map.of(),List.of());
        assertThat(finished.execution()).isEqualTo(stored.execution());
        assertThat(finished.finished("FAILED",clock.instant().plusSeconds(90),Map.of(),List.of()).reportExpiresAt()).isEqualTo(finished.reportExpiresAt());
    }
    @Test void T_18_13_resumeRejectsDriftRepeatedWorkStaleAttemptAndOtherTenant() {
        partial("first");
        rejected(()->runs.authorizeExecution("next",request("4","first"),new InputManifest("c".repeat(64),manifest.files()),CUTOVER,true,clock.instant()),ErrorCode.INPUT_SCHEMA_MISMATCH);
        rejected(()->runs.authorizeExecution("next",request("4","first"),manifest,CUTOVER.plusDays(1),true,clock.instant()),ErrorCode.INPUT_SCHEMA_MISMATCH);
        for(String selection:List.of("1","3","3-4")) { rejected(()->runs.authorizeExecution("next",request(selection,"first"),manifest,CUTOVER,true,clock.instant()),ErrorCode.MIGRATION_ALREADY_APPLIED); }
        var second=runs.authorizeExecution("second",request("4","first"),manifest,CUTOVER,true,clock.instant()); runs.insert(run("second","FAILED",second));
        rejected(()->runs.authorizeExecution("next",request("5","first"),manifest,CUTOVER,true,clock.instant()),ErrorCode.MIGRATION_ALREADY_APPLIED);
        assertThat(runs.authorizeExecution("next",request("4","second"),manifest,CUTOVER,true,clock.instant()).priorRunId()).isEqualTo("second");
        TenantContext.clear();
        try(var other=TenantContext.open(OTHER)) { rejected(()->runs.authorizeExecution("next",request("4","second"),manifest,CUTOVER,true,clock.instant()),ErrorCode.MIGRATION_ALREADY_APPLIED); }
        finally { TenantContext.open(CLUB); }
    }
    @Test void T_18_14_productionOverrideRequiresApprovalBoundToLatestPriorAndCandidate() {
        partial("first");
        rejected(()->runs.authorizeExecution("next",request("4",null),manifest,CUTOVER,true,clock.instant()),ErrorCode.MIGRATION_ALREADY_APPLIED);
        var approval=new ReapplyApproval(CLUB,"first",ReapplyApproval.manifestSha256(manifest),CUTOVER,MigrationSteps.all(),"Fictional operator",
                clock.instant(),"Fictional correction","fictional-runbook-entry","d".repeat(64));
        var approved=new MigrationExecutionRequest(MigrationSteps.all(),false,null,approval);
        var next=runs.authorizeExecution("second",approved,manifest,CUTOVER,true,clock.instant());
        assertThat(next.rootRunId()).isEqualTo("second"); assertThat(next.priorRunId()).isEqualTo("first"); assertThat(next.completedBefore()).isEmpty();
        assertThat(next.approvalDigest()).isEqualTo("d".repeat(64)); assertThat(next.toString()).doesNotContain("Fictional operator","Fictional correction");
        runs.insert(run("second","FAILED",next));
        rejected(()->runs.authorizeExecution("third",approved,manifest,CUTOVER,true,clock.instant()),ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION);
        rejected(()->runs.authorizeExecution("third",approved,manifest,CUTOVER,false,clock.instant()),ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION);
    }
    @Test void T_18_14_legacyGuardRefusesAdvancedFailedAttemptsButPreservesLegacyFailedRetry() {
        runs.insert(run("legacy-failed","FAILED",null)); assertThatCode(()->runs.interrupted(true)).doesNotThrowAnyException();
        var execution=runs.authorizeExecution("advanced",request("1-3",null),manifest,CUTOVER,true,clock.instant());
        runs.insert(run("advanced","FAILED",execution)); rejected(()->runs.interrupted(true),ErrorCode.MIGRATION_ALREADY_APPLIED);
        rejected(()->runs.interrupted(false),ErrorCode.MIGRATION_ALREADY_APPLIED);
        assertThatCode(runs::interruptedForExecution).doesNotThrowAnyException();
    }
    @Test void T_18_14_legacyCompletedProductionRunCannotBeResumedAsPartial() {
        runs.insert(run("legacy-complete","COMPLETED",null));
        rejected(()->runs.authorizeExecution("next",request("4","legacy-complete"),manifest,CUTOVER,true,clock.instant()),ErrorCode.MIGRATION_ALREADY_APPLIED);
        rejected(()->runs.authorizeExecution("next",request("1-3",null),manifest,CUTOVER,true,clock.instant()),ErrorCode.MIGRATION_ALREADY_APPLIED);
    }
    @Test void T_18_13_stagingLegacyGuardRejectsIncompleteLineageAndAcceptsCompletedLineage() {
        var execution=runs.authorizeExecution("staging",request("1-3",null),manifest,CUTOVER,false,clock.instant());
        var staging=new MigrationRun("staging",CLUB,"PLAYOFF","APPLY","STAGING",2,"COMPLETED",clock.instant(),clock.instant(),
                Map.of(),manifest,List.of(),null,null,null,execution.stepCompleted(1).stepCompleted(2).stepCompleted(3));
        runs.insert(staging);
        rejected(()->runs.interrupted(false),ErrorCode.MIGRATION_ALREADY_APPLIED);
        var resumed=runs.authorizeExecution("resumed",request("4-10","staging"),manifest,CUTOVER,false,clock.instant());
        for(int step:resumed.selectedSteps()) { resumed=resumed.stepCompleted(step); }
        runs.insert(new MigrationRun("resumed",CLUB,"PLAYOFF","APPLY","STAGING",2,"COMPLETED",clock.instant(),clock.instant(),
                Map.of(),manifest,List.of(),null,null,null,resumed));
        assertThatCode(()->runs.interrupted(false)).doesNotThrowAnyException();
    }
    @Test void T_18_14_ambiguousLegacyTimestampAndDuplicateExecutionOrdinalFailClosed() {
        runs.insert(run("legacy-one","FAILED",null)); runs.insert(run("legacy-two","FAILED",null));
        rejected(()->runs.authorizeExecution("next",request("1-3",null),manifest,CUTOVER,true,clock.instant()),ErrorCode.MIGRATION_ALREADY_APPLIED);
        var execution=new MigrationExecution(new TreeSet<>(Set.of(1,2)),new TreeSet<>(),new TreeSet<>(),CUTOVER,"advanced-one",null,1,null,true);
        runs.insert(run("advanced-one","FAILED",execution)); runs.insert(run("advanced-two","FAILED",execution));
        rejected(()->runs.authorizeExecution("next",request("3","advanced-one"),manifest,CUTOVER,true,clock.instant()),ErrorCode.MIGRATION_ALREADY_APPLIED);
    }
}

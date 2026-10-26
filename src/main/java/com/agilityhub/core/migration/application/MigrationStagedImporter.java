package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.catalogs.application.MigrationCatalogAccess;
import com.agilityhub.core.clubs.census.application.CensusMigrationService;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.payments.application.BillingMigrationAccess;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** S18 explicit stages: immutable input lineage, independently committed batches, no implicit production override. */
@Service
public class MigrationStagedImporter {
    private final PlayoffPlanner planner;
    private final MigrationCensusSteps stages;
    private final PlayoffBillingPlanner billingPlanner;
    private final MigrationApplyService apply;
    private final MigrationRunRepository runs;
    private final MigrationResetRepository reset;
    private final CensusMigrationService census;
    private final MigrationCatalogAccess catalogs;
    private final BillingMigrationAccess billing;
    private final ClubConfigService configs;
    private final TransactionTemplate transactions;
    private final MigrationReports reports;
    private final EventPublisher events;
    private final Clock clock;

    public MigrationStagedImporter(PlayoffPlanner planner,MigrationCensusSteps stages,PlayoffBillingPlanner billingPlanner,
            MigrationApplyService apply,MigrationRunRepository runs,MigrationResetRepository reset,
            CensusMigrationService census,MigrationCatalogAccess catalogs,BillingMigrationAccess billing,
            ClubConfigService configs,TransactionTemplate transactions,MigrationReports reports,EventPublisher events,Clock clock) {
        this.planner=planner; this.stages=stages; this.billingPlanner=billingPlanner; this.apply=apply; this.runs=runs;
        this.reset=reset; this.census=census; this.catalogs=catalogs; this.billing=billing; this.configs=configs;
        this.transactions=transactions; this.reports=reports; this.events=events; this.clock=clock;
    }

    public MigrationReport importDirectory(Path directory,MappingConfig mapping,String clubId,boolean dryRun,
            boolean production,boolean confirmed,LocalDate cutover,MigrationExecutionRequest request) {
        Objects.requireNonNull(request);
        if (!request.staged()) { throw new IllegalArgumentException("Advanced migration execution must select stages explicitly"); }
        if (production && !dryRun && !confirmed) { throw new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION); }
        mapping.validate();
        var input=PlayoffInput.read(MigrationPaths.input(directory),mapping);
        try (var tenant=TenantContext.open(clubId)) {
            // These non-personal values bind the operator's private reapply confirmation to precisely this candidate.
            System.out.println("Migration input manifest SHA-256: "+ReapplyApproval.manifestSha256(input.manifest()));
            System.out.println("Migration cut-over: "+cutover+"; selected steps: "+request.steps().values());
            var preview=prepare(input,mapping,cutover,request,dryRun);
            if (preview.report().hasErrors()) { return preview.report(); }
            if (request.resumeRunId()!=null || request.approval()!=null) {
                runs.previewExecution(request,input.manifest(),cutover,production,clock.instant());
            }
            if (dryRun) { return preview.report(); }
            apply.validate(preview.census().preview());
            if (production) { reports.requireReady(); }
            String holder=UUID.randomUUID().toString();
            var started=new AtomicReference<MigrationRun>(); var activeStep=new AtomicInteger(1);
            boolean committed=false;
            runs.acquire(holder,clock.instant());
            try {
                var prepared=transactions.execute(status->{
                    runs.fence(holder,clock.instant()); reset.trackWrites();
                    if (production && request.resumeRunId()!=null) { reset.requireNoLaterWrites(); }
                    String id=UUID.randomUUID().toString();
                    var execution=runs.authorizeExecution(id,request,input.manifest(),cutover,production,clock.instant());
                    census.lock(); catalogs.lock();
                    var checked=prepare(input,mapping,cutover,request,false);
                    if (checked.report().hasErrors()) { status.setRollbackOnly(); return checked; }
                    for (var abandoned:runs.interruptedForExecution()) { failed(abandoned,0); }
                    var run=new MigrationRun(id,clubId,"PLAYOFF","APPLY",production ? "PRODUCTION" : "STAGING",
                            mapping.version(),"RUNNING",clock.instant(),null,Map.of(),input.manifest(),checked.report().rows(),
                            null,null,null,execution);
                    runs.insert(run); started.set(run);
                    events.publish(new MigrationEvent("MigrationRunStarted",id,clubId,clock.instant(),Map.of("mode",run.mode(),"env",run.env())));
                    runs.stepCompleted(id,1);
                    reset.checkpoint(production,clock.instant()); runs.fence(holder,clock.instant());
                    return checked;
                });
                var run=started.get();
                if (run==null) { return prepared.report(); }
                System.out.println("Migration run: "+run.id());
                var rows=new ArrayList<>(prepared.report().rows()); var totals=new TreeMap<>(prepared.report().totals());
                boolean reconciled=false;
                for (int step:request.steps().values()) {
                    if (step==1) { continue; }
                    activeStep.set(step);
                    if (step>=2 && step<=6) {
                        batches(holder,production,prepared.census().changes(step),page->apply.applyChanges(run,page));
                    } else if (step==7) {
                        batches(holder,production,prepared.billing().receipts(),page->page.forEach(billing::receipt));
                    } else if (step==8) {
                        batches(holder,production,prepared.billing().packs(),page->page.forEach(billing::pack));
                    } else if (step==9) {
                        batches(holder,production,prepared.census().identities(),apply::applyIdentities);
                    } else if (step==10 && !prepared.billing().forecast().isEmpty()) {
                        reconciled=reconcile(holder,production,clubId,cutover,prepared.billing().forecast(),rows,totals);
                    }
                    if (step!=request.steps().values().last()) {
                        final int completedStep=step;
                        write(holder,production,()->completeStep(run.id(),completedStep,prepared));
                    }
                }
                var report=new MigrationReport(false,rows,totals); boolean withinTolerance=reconciled;
                write(holder,production,()->{
                    // Keep the final stage unfinished if terminal audit/event recording fails: an explicit resume can recover it.
                    int last=request.steps().values().last();
                    if (last!=1) { completeStep(run.id(),last,prepared); }
                    apply.complete(run,report,withinTolerance);
                });
                committed=true;
                if (production) {
                    try { reports.publish(run.id(),input); }
                    catch (RuntimeException failure) {
                        System.err.println("Migration data committed; recover only the report with migration:report for run "+run.id()+". Do not reapply the migration.");
                        throw failure;
                    }
                }
                System.out.println("Migration completed steps: "+runs.findById(run.id()).orElseThrow().execution().completedSteps());
                return report;
            } catch (RuntimeException failure) {
                var run=started.get();
                if (run!=null && !committed) {
                    System.err.println("Migration run requiring review: "+run.id()+"; step "+activeStep.get());
                    try {
                        transactions.executeWithoutResult(status->{
                            if (!runs.tryFence(holder,clock.instant())) { return; }
                            reset.trackWrites(); boolean clean=true;
                            if (production) {
                                try { reset.requireNoLaterWrites(); }
                                catch (ApiException changed) {
                                    if (changed.code()!=ErrorCode.MIGRATION_ALREADY_APPLIED) { throw changed; }
                                    clean=false;
                                }
                            }
                            runs.findById(run.id()).ifPresent(current->failed(current,activeStep.get()));
                            // Failure metadata must never absorb a concurrent operator's write into a clean checkpoint.
                            if (clean) { reset.checkpoint(production,clock.instant()); }
                            runs.fence(holder,clock.instant());
                        });
                    } catch (RuntimeException recordingFailure) { failure.addSuppressed(recordingFailure); }
                }
                throw failure;
            } finally { runs.release(holder); }
        }
    }

    private record Prepared(MigrationCensusSteps.Selection census,PlayoffBillingPlanner.Plan billing,MigrationReport report) { }
    private Prepared prepare(PlayoffInput input,MappingConfig mapping,LocalDate cutover,MigrationExecutionRequest request,boolean dry) {
        var full=planner.plan(input,mapping,cutover);
        if (full.rows().stream().anyMatch(row->row.outcome().equals("ERROR"))) {
            return new Prepared(null,null,new MigrationReport(dry,full.rows()));
        }
        var modules=configs.get(TenantContext.require()).modules();
        boolean billingEnabled=modules.contains(com.agilityhub.core.platform.application.Module.BILLING);
        var effectiveValues=new TreeSet<>(request.steps().values());
        if (!billingEnabled) { effectiveValues.removeAll(Set.of(5,7)); }
        if (!modules.contains(com.agilityhub.core.platform.application.Module.PACKS)) { effectiveValues.remove(8); }
        var effectiveSteps=new MigrationSteps(effectiveValues);
        var selection=stages.select(full,effectiveSteps);
        validateBillingDependencies(input,full,selection,effectiveSteps);
        var available=new TreeSet<>(request.steps().values());
        if (request.resumeRunId()!=null) {
            runs.findById(request.resumeRunId()).filter(run->run.execution()!=null)
                    .ifPresent(run->available.addAll(run.execution().completedSteps()));
        }
        // Optional future bookings remain disabled. Step 10 closes the supported pipeline and runs its reconciliation.
        if (request.steps().includes(10) && !available.containsAll(MigrationSteps.all().values())) {
            throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH);
        }
        var files=new LinkedHashMap<>(input.files());
        if (!effectiveSteps.includes(7)) { files.put("receipts",List.of()); }
        if (!effectiveSteps.includes(8)) { files.put("packs",List.of()); }
        if (!request.steps().includes(10) || !billingEnabled) { files.put("forecast",List.of()); }
        var selectedInput=new PlayoffInput(files,input.incidents(),input.manifest());
        var plannedBilling=billingPlanner.plan(selectedInput,mapping,selection.preview(),cutover);
        var rows=new ArrayList<>(selection.rows()); rows.addAll(plannedBilling.rows());
        return new Prepared(selection,plannedBilling,new MigrationReport(dry,rows,plannedBilling.counters()));
    }

    private void validateBillingDependencies(PlayoffInput input,PlayoffPlanner.Plan full,
            MigrationCensusSteps.Selection selection,MigrationSteps steps) {
        if (!steps.includes(7) && !steps.includes(8)) { return; }
        var members=new HashMap<String,Map<String,Object>>(); var dogs=new HashSet<String>();
        census.snapshot("members").forEach(row->{ if (imported(row)) { members.put(Objects.toString(row.get("_id")),row); } });
        census.snapshot("dogs").forEach(row->{ if (imported(row)) { dogs.add(Objects.toString(row.get("_id"))); } });
        selection.preview().changes().forEach(change->{
            if (change.entity().equals("members")) { members.put(change.id(),change.fields()); }
            if (change.entity().equals("dogs")) { dogs.add(change.id()); }
        });
        var effectiveSources=new HashMap<String,String>();
        members.forEach((id,fields)->{
            if (fields.get("sourceIds") instanceof Map<?,?> ids && ids.get("playoffMemberId")!=null) {
                effectiveSources.put(ids.get("playoffMemberId").toString(),id);
            }
            if (fields.get("externalIds") instanceof Map<?,?> ids && ids.get("playoff") instanceof List<?> aliases) {
                aliases.forEach(alias->effectiveSources.put(alias.toString(),id));
            }
        });
        var bySource=new HashMap<String,String>(); var plannedDogs=new HashMap<String,Set<String>>();
        full.changes().forEach(change->{
            if (change.entity().equals("members")) {
                bySource.put(change.source().get("id"),change.id());
                Object external=change.fields().get("externalIds");
                if (external instanceof Map<?,?> ids && ids.get("playoff") instanceof List<?> aliases) {
                    aliases.forEach(alias->bySource.put(alias.toString(),change.id()));
                }
            } else if (change.entity().equals("dogs")) {
                plannedDogs.computeIfAbsent(Objects.toString(change.fields().get("memberId")),id->new HashSet<>()).add(change.id());
            }
        });
        for (int step:List.of(7,8)) {
            if (!steps.includes(step)) { continue; }
            for (var row:input.files().getOrDefault(step==7 ? "receipts" : "packs",List.of())) {
                String member=bySource.get(row.get("memberId"));
                // Truly unknown source IDs keep the existing per-row WARNING/SKIPPED contract.
                if (member==null) { continue; }
                // A known merged source alias still needs step 3 if the selected/effective person does not own it yet.
                if (!member.equals(effectiveSources.get(row.get("memberId")))) { throw dependency(step,3); }
                if (step==8 && !dogs.containsAll(plannedDogs.getOrDefault(member,Set.of()))) { throw dependency(8,4); }
            }
        }
    }
    private boolean imported(Map<String,Object> row) {
        return row.get("sourceIds")!=null && row.get("erasedAt")==null && !"ERASED".equals(row.get("status"));
    }
    private ApiException dependency(int step,int required) {
        return new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH,Map.of("field","steps","step",step,"requiredStep",required));
    }

    private <T> void batches(String holder,boolean production,List<T> values,Consumer<List<T>> writer) {
        for (int start=0;start<values.size();start+=100) {
            var page=values.subList(start,Math.min(start+100,values.size()));
            write(holder,production,()->writer.accept(page));
        }
    }
    private void completeStep(String runId,int step,Prepared prepared) {
        if (step==3) { apply.reserveNumbers(prepared.census().maximumNumber()); }
        runs.stepCompleted(runId,step);
    }
    private void write(String holder,boolean production,Runnable action) {
        transactions.executeWithoutResult(status->{
            runs.fence(holder,clock.instant()); reset.trackWrites();
            if (production) { reset.requireNoLaterWrites(); }
            action.run();
            reset.checkpoint(production,clock.instant()); runs.fence(holder,clock.instant());
        });
    }
    private void failed(MigrationRun run,int step) {
        runs.replace(run.finished("FAILED",clock.instant(),Map.of(),run.reportRows()));
        events.publish(new MigrationEvent("MigrationRunFailed",run.id(),run.clubId(),clock.instant(),Map.of("step",Integer.toString(step))));
    }
    private boolean reconcile(String holder,boolean production,String clubId,LocalDate cutover,Map<String,Long> forecast,
            List<MigrationReport.Entry> rows,Map<String,Long> totals) {
        var result=transactions.execute(status->{
            runs.fence(holder,clock.instant()); reset.trackWrites();
            if (production) { reset.requireNoLaterWrites(); }
            var reconciliation=billing.reconcile(YearMonth.from(cutover).plusMonths(1));
            reset.checkpoint(production,clock.instant()); runs.fence(holder,clock.instant());
            return reconciliation;
        });
        long expected=forecast.values().stream().reduce(0L,Math::addExact);
        long difference=Math.subtractExact(result.totalMinor(),expected);
        var tolerance=new java.math.BigDecimal(configs.get(clubId).get("migration.reconciliationTolerancePct",Number.class).toString());
        boolean reconciled=java.math.BigDecimal.valueOf(difference).abs().multiply(java.math.BigDecimal.valueOf(100))
                .compareTo(java.math.BigDecimal.valueOf(expected).abs().multiply(tolerance))<=0;
        totals.put("reconciliationExpectedMinor",expected); totals.put("reconciliationActualMinor",result.totalMinor());
        totals.put("reconciliationDifferenceMinor",difference);
        if (!reconciled) {
            var memberIds=new TreeSet<>(forecast.keySet()); memberIds.addAll(result.byMember().keySet());
            for (String id:memberIds) {
                if (!Objects.equals(forecast.getOrDefault(id,0L),result.byMember().getOrDefault(id,0L))) {
                    String code=result.incidents().getOrDefault(id,"MAPPING_INVALID");
                    rows.add(new MigrationReport.Entry("forecast",0,"reconciliation","WARNING",code.equals("NO_PLAN") ? "LEGACY_PLAN" : code,id));
                }
            }
        }
        return reconciled;
    }
}

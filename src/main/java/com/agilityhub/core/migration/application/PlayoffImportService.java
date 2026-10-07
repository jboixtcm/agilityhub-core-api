package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.CensusMigrationService;
import com.agilityhub.core.clubs.catalogs.application.MigrationCatalogAccess;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PlayoffImportService {
    private final PlayoffPlanner planner;
    private final MigrationApplyService apply;
    private final MigrationRunRepository runs;
    private final CensusMigrationService census;
    private final MigrationCatalogAccess catalogs;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final EventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired private MigrationResetRepository resetGuard;
    @org.springframework.beans.factory.annotation.Autowired private PlayoffBillingPlanner billingPlanner;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.payments.application.BillingMigrationAccess billing;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.platform.application.ClubConfigService configs;
    @org.springframework.beans.factory.annotation.Autowired private ClubClock clubClock;
    public PlayoffImportService(PlayoffPlanner planner, MigrationApplyService apply, MigrationRunRepository runs,
            CensusMigrationService census, MigrationCatalogAccess catalogs, TransactionTemplate transactions,
            Clock clock, EventPublisher events) {
        this.planner=planner; this.apply=apply; this.runs=runs; this.census=census; this.catalogs=catalogs;
        this.transactions=transactions; this.clock=clock; this.events=events;
    }
    public MigrationReport importDirectory(Path directory, MappingConfig mapping, String clubId,
            boolean dryRun, boolean production, boolean confirmed) {
        return importDirectory(directory, mapping, clubId, dryRun, production, confirmed, clubClock.today(clubId));
    }
    public MigrationReport importDirectory(Path directory, MappingConfig mapping, String clubId,
            boolean dryRun, boolean production, boolean confirmed, LocalDate cutover) {
        if (!dryRun && production && !confirmed) { throw new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION); }
        mapping.validate();
        var input = PlayoffInput.read(MigrationPaths.input(directory), mapping);
        try (var tenant = TenantContext.open(clubId)) {
            var preview = planner.plan(input, mapping, cutover);
            var billingPreview = billingPlanner.plan(input, mapping, preview, cutover);
            var report = report(dryRun, preview, billingPreview);
            if (dryRun || report.hasErrors()) { return report; }
            apply.validate(preview);
            var started = new java.util.concurrent.atomic.AtomicReference<MigrationRun>();
            try {
                // Census retains its existing atomic boundary. Billing stages follow in 100-row transactions.
                var prepared = transactions.execute(status -> {
                    runs.lock(production); census.lock(); catalogs.lock();
                    var plan = planner.plan(input, mapping, cutover);
                    var billingPlan = billingPlanner.plan(input, mapping, plan, cutover);
                    var checked = report(false, plan, billingPlan);
                    if (checked.hasErrors()) { status.setRollbackOnly(); return new Prepared(null, checked, billingPlan); }
                    var run = new MigrationRun(UUID.randomUUID().toString(), clubId, "PLAYOFF", "APPLY",
                            production ? "PRODUCTION" : "STAGING", mapping.version(), "RUNNING", clock.instant(), null, Map.of());
                    runs.insert(run); started.set(run);
                    events.publish(new MigrationEvent("MigrationRunStarted", run.id(), clubId, clock.instant(), Map.of("mode", run.mode(), "env", run.env())));
                    apply.apply(run, plan);
                    return new Prepared(run, checked, billingPlan);
                });
                if (prepared.run() == null) { return prepared.report(); }
                batch(prepared.billing().receipts(), billing::receipt);
                batch(prepared.billing().packs(), billing::pack);
                var rows = new ArrayList<>(prepared.report().rows()); var totals = new TreeMap<>(prepared.report().totals());
                boolean reconciled = false;
                if (!prepared.billing().forecast().isEmpty()) {
                    var result = billing.reconcile(YearMonth.from(cutover).plusMonths(1));
                    long expected = prepared.billing().forecast().values().stream().reduce(0L, Math::addExact);
                    long difference = Math.subtractExact(result.totalMinor(), expected);
                    var tolerance = new java.math.BigDecimal(configs.get(clubId).get("migration.reconciliationTolerancePct", Number.class).toString());
                    reconciled = java.math.BigDecimal.valueOf(difference).abs().multiply(java.math.BigDecimal.valueOf(100))
                            .compareTo(java.math.BigDecimal.valueOf(expected).abs().multiply(tolerance)) <= 0;
                    totals.put("reconciliationExpectedMinor", expected); totals.put("reconciliationActualMinor", result.totalMinor());
                    totals.put("reconciliationDifferenceMinor", difference);
                    if (!reconciled) {
                        var memberIds = new TreeSet<>(prepared.billing().forecast().keySet()); memberIds.addAll(result.byMember().keySet());
                        for (String id : memberIds) {
                            if (!Objects.equals(prepared.billing().forecast().getOrDefault(id, 0L), result.byMember().getOrDefault(id, 0L))) {
                                String code = result.incidents().getOrDefault(id, "MAPPING_INVALID");
                                if (code.equals("NO_PLAN")) { code = "LEGACY_PLAN"; }
                                rows.add(new MigrationReport.Entry("forecast", 0, "reconciliation", "WARNING", code, id));
                            }
                        }
                    }
                }
                var completed = new MigrationReport(false, rows, totals); boolean withinTolerance = reconciled;
                transactions.executeWithoutResult(status -> apply.complete(prepared.run(), completed, withinTolerance));
                resetGuard.checkpoint(production);
                return completed;
            } catch (RuntimeException failure) {
                var run = started.get();
                if (run != null) {
                    transactions.executeWithoutResult(status -> {
                        runs.replace(new MigrationRun(run.id(), clubId, run.source(), run.mode(), run.env(), run.mappingVersion(),
                                "FAILED", run.startedAt(), clock.instant(), Map.of()));
                        events.publish(new MigrationEvent("MigrationRunFailed", run.id(), clubId, clock.instant(), Map.of("step", "LOAD")));
                    });
                }
                throw failure;
            }
        }
    }
    private record Prepared(MigrationRun run, MigrationReport report, PlayoffBillingPlanner.Plan billing) { }
    private MigrationReport report(boolean dry, PlayoffPlanner.Plan censusPlan, PlayoffBillingPlanner.Plan billingPlan) {
        var rows = new ArrayList<>(censusPlan.rows()); rows.addAll(billingPlan.rows());
        return new MigrationReport(dry, rows, billingPlan.counters());
    }
    private <T> void batch(List<T> values, java.util.function.Consumer<T> writer) {
        for (int start = 0; start < values.size(); start += 100) {
            var page = values.subList(start, Math.min(start + 100, values.size()));
            transactions.executeWithoutResult(status -> page.forEach(writer));
        }
    }
}

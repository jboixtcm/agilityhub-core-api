package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.CensusMigrationService;
import com.agilityhub.core.clubs.catalogs.application.MigrationCatalogAccess;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.Path;
import java.time.Clock;
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
    public PlayoffImportService(PlayoffPlanner planner, MigrationApplyService apply, MigrationRunRepository runs,
            CensusMigrationService census, MigrationCatalogAccess catalogs, TransactionTemplate transactions,
            Clock clock, EventPublisher events) {
        this.planner=planner; this.apply=apply; this.runs=runs; this.census=census; this.catalogs=catalogs;
        this.transactions=transactions; this.clock=clock; this.events=events;
    }
    public MigrationReport importDirectory(Path directory, MappingConfig mapping, String clubId,
            boolean dryRun, boolean production, boolean confirmed) {
        if (!dryRun && production && !confirmed) { throw new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION); }
        mapping.validate();
        var input=PlayoffInput.read(directory,mapping);
        try (var tenant=TenantContext.open(clubId)) {
            var preview=planner.plan(input,mapping);
            var report=new MigrationReport(dryRun,preview.rows());
            if (dryRun || report.hasErrors()) { return report; }
            apply.validate(preview);
            // Rebuild after acquiring the same write locks used by census and catalogs.
            // This census-only stage is atomic; later billing stages own batch checkpoints.
            var started=new java.util.concurrent.atomic.AtomicReference<MigrationRun>();
            try { return transactions.execute(status -> {
                runs.lock(production); census.lock(); catalogs.lock();
                var plan=planner.plan(input,mapping);
                var checked=new MigrationReport(false,plan.rows());
                if (checked.hasErrors()) { status.setRollbackOnly(); return checked; }
                var run=new MigrationRun(UUID.randomUUID().toString(),clubId,"PLAYOFF","APPLY",
                        production ? "PRODUCTION" : "STAGING",mapping.version(),"RUNNING",clock.instant(),null,Map.of());
                runs.insert(run);
                started.set(run);
                events.publish(new MigrationEvent("MigrationRunStarted",run.id(),clubId,clock.instant(),Map.of("mode",run.mode(),"env",run.env())));
                apply.apply(run,plan);
                return checked;
            }); } catch (RuntimeException failure) {
                var run=started.get();
                if (run!=null) {
                    transactions.executeWithoutResult(status -> {
                        runs.insert(new MigrationRun(run.id(),clubId,run.source(),run.mode(),run.env(),run.mappingVersion(),
                                "FAILED",run.startedAt(),clock.instant(),Map.of()));
                        events.publish(new MigrationEvent("MigrationRunFailed",run.id(),clubId,clock.instant(),Map.of("step","CENSUS")));
                    });
                }
                throw failure;
            }
        }
    }
}

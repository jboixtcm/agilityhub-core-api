package com.agilityhub.core.migration.application;

import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * MIG-13 (owner decision, 13-11-2026): purge a production run's named `report.xlsx` right after the run, without waiting for
 * the 30-day retention ({@link MigrationReports#purgeNow}). Idempotent: a second run reports it already purged.
 */
@Component
public class MigrationReportPurgeCommand implements CoreCommand {
    private final MigrationReports reports; private final MigrationClubAccess clubs;
    public MigrationReportPurgeCommand(MigrationReports reports, MigrationClubAccess clubs) { this.reports = reports; this.clubs = clubs; }
    @Override public String name() { return "migration:report-purge"; }
    @Override public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().isEmpty() || !Set.of("core.command", "club", "run").containsAll(args.getOptionNames())) { throw usage(); }
        String slug = option(args, "club"), run = option(args, "run");
        try (var scope = TenantContext.open(clubs.resolve(slug))) {
            System.out.println(reports.purgeNow(run) ? "Migration report purged for run " + run : "Migration report of run " + run + " was already purged");
        }
    }
    private String option(ApplicationArguments args, String name) {
        var values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) { throw usage(); }
        return values.getFirst();
    }
    private IllegalArgumentException usage() { return new IllegalArgumentException("Usage: migration:report-purge --club=slug --run=id"); }
}

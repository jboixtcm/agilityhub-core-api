package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.domain.MappingConfig;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.application.*;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/** Rebuild a missing production report without applying or replanning imported business data. */
@Component
public class MigrationReportCommand implements CoreCommand {
    private final MigrationReports reports; private final MigrationClubAccess clubs;
    public MigrationReportCommand(MigrationReports reports,MigrationClubAccess clubs) { this.reports=reports; this.clubs=clubs; }
    public String name() { return "migration:report"; }
    public void run(ApplicationArguments args) {
        if (!args.getNonOptionArgs().isEmpty() || !Set.of("core.command","club","run","in","mapping").containsAll(args.getOptionNames())) { throw usage(); }
        String mappingPath=option(args,"mapping",false);
        var mapping=MappingConfig.load(mappingPath==null ? null : Path.of(mappingPath)); mapping.validate();
        String club=clubs.resolve(option(args,"club",true)); String run=option(args,"run",true);
        var input=PlayoffInput.read(MigrationPaths.input(Path.of(option(args,"in",true))),mapping);
        try (var scope=TenantContext.open(club)) { reports.publish(run,input); }
        System.out.println("Migration report published for run "+run);
    }
    private String option(ApplicationArguments args,String name,boolean required) {
        var values=args.getOptionValues(name);
        if (values==null && !required) { return null; }
        if (values==null || values.size()!=1 || values.getFirst().isBlank()) { throw usage(); }
        return values.getFirst();
    }
    private IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: migration:report --club=slug --run=id --in=private-directory [--mapping=file]");
    }
}

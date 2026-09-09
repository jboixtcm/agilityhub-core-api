package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.domain.MappingConfig;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class PlayoffCommand implements CoreCommand {
    private final PlayoffImportService importer;
    private final MigrationClubAccess clubs;
    private final Environment environment;
    public PlayoffCommand(PlayoffImportService importer, MigrationClubAccess clubs, Environment environment) {
        this.importer=importer; this.clubs=clubs; this.environment=environment;
    }
    public String name() { return "migration:playoff"; }
    public void run(ApplicationArguments args) {
        if (args.getNonOptionArgs().size()!=1 || !Set.of("core.command","dry-run","club","mapping","env","confirm-production").containsAll(args.getOptionNames())) { throw usage(); }
        for (String flag:Set.of("dry-run","confirm-production")) {
            if (args.containsOption(flag) && !args.getOptionValues(flag).isEmpty()) { throw usage(); }
        }
        String mappingPath=option(args,"mapping",null);
        var mapping=MappingConfig.load(mappingPath==null ? null : Path.of(mappingPath));
        String env=option(args,"env","staging");
        if (!Set.of("staging","production").contains(env)) { throw usage(); }
        boolean production=env.equals("production") || environment.matchesProfiles("prod","production");
        var report=importer.importDirectory(Path.of(args.getNonOptionArgs().getFirst()),mapping,
                clubs.resolve(option(args,"club",mapping.defaultClub())),args.containsOption("dry-run"),production,args.containsOption("confirm-production"));
        System.out.print(report.render());
        if (report.hasErrors()) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
    }
    private String option(ApplicationArguments args,String name,String fallback) {
        var values=args.getOptionValues(name);
        if (values==null) { return fallback; }
        if (values.size()!=1 || values.getFirst().isBlank()) { throw usage(); }
        return values.getFirst();
    }
    private IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: migration:playoff <dir> [--dry-run] [--club=slug] [--mapping=file] [--env=staging|production] [--confirm-production]");
    }
}

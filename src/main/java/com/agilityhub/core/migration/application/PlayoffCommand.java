package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.domain.*;
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
    private final MigrationResetService reset;
    public PlayoffCommand(PlayoffImportService importer, MigrationClubAccess clubs, Environment environment, MigrationResetService reset) {
        this.importer=importer; this.clubs=clubs; this.environment=environment; this.reset=reset;
    }
    public String name() { return "migration:playoff"; }
    public void run(ApplicationArguments args) {
        if (args.getNonOptionArgs().size()>1 || !Set.of("core.command","dry-run","apply","in","cut-over","reset","seed","club","mapping","env","confirm-production").containsAll(args.getOptionNames())) { throw usage(); }
        for (String flag:Set.of("dry-run","apply","reset","confirm-production")) {
            if (args.containsOption(flag) && !args.getOptionValues(flag).isEmpty()) { throw usage(); }
        }
        String mappingPath=option(args,"mapping",null);
        var mapping=MappingConfig.load(mappingPath==null ? null : Path.of(mappingPath));
        String env=option(args,"env","staging");
        if (!Set.of("staging","production").contains(env)) { throw usage(); }
        boolean production=env.equals("production") || environment.matchesProfiles("prod","production");
        String slug=option(args,"club",mapping.defaultClub()); String clubId=clubs.resolve(slug);
        if (args.containsOption("reset")) {
            if (args.containsOption("dry-run") || args.containsOption("apply") || args.containsOption("in") || !args.getNonOptionArgs().isEmpty()) { throw usage(); }
            if (environment.matchesProfiles("prod","production")) { throw new ApiException(ErrorCode.FORBIDDEN); }
            try {
                var reader=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));
                System.out.println("Reset club data. Enter the club slug:"); String first=reader.readLine();
                System.out.println("Enter the club slug again:"); String second=reader.readLine();
                reset.reset(slug,Path.of(option(args,"seed","seeds/club-"+slug+".yaml")),first,second);
                System.out.println("Club reset completed: "+slug); return;
            } catch (java.io.IOException failure) { throw new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION); }
        }
        if (args.containsOption("apply") && args.containsOption("dry-run")) { throw usage(); }
        String directory=option(args,"in",args.getNonOptionArgs().isEmpty() ? null : args.getNonOptionArgs().getFirst());
        if (directory==null || args.containsOption("in") && !args.getNonOptionArgs().isEmpty()) { throw usage(); }
        // R-18-08/13: the cut-over date (mandates' signature date, first nextInvoiceDate); the club's today when omitted.
        String cutover=option(args,"cut-over",null); boolean dryRun=args.containsOption("dry-run"), confirmed=args.containsOption("confirm-production");
        MigrationReport report;
        try {
            report=cutover==null ? importer.importDirectory(Path.of(directory),mapping,clubId,dryRun,production,confirmed)
                    : importer.importDirectory(Path.of(directory),mapping,clubId,dryRun,production,confirmed,java.time.LocalDate.parse(cutover));
        } catch(java.time.format.DateTimeParseException invalid) { throw usage(); }
        System.out.print(report.render());
        // Any error row, REEXECUTION_UNSUPPORTED included, means nothing was applied: the command exits non-zero.
        if (report.hasErrors()) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
    }
    private String option(ApplicationArguments args,String name,String fallback) {
        var values=args.getOptionValues(name);
        if (values==null) { return fallback; }
        if (values.size()!=1 || values.getFirst().isBlank()) { throw usage(); }
        return values.getFirst();
    }
    private IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: migration:playoff [<dir>|--in=dir] [--apply|--dry-run] [--cut-over=YYYY-MM-DD] [--club=slug] [--mapping=file] [--env=staging|production] [--confirm-production] | --reset --club=slug [--seed=file]");
    }
}

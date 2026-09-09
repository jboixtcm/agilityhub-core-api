package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.domain.MappingConfig;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class PlayoffImportCommand implements CoreCommand {
    private final PlayoffImportService importer; private final Environment environment;
    public PlayoffImportCommand(PlayoffImportService importer,Environment environment) { this.importer=importer; this.environment=environment; }
    @Override public String name() { return "migration:playoff"; }
    @Override public void run(ApplicationArguments args) {
        if (args.getNonOptionArgs().size()!=1 || !Set.of("core.command","dry-run","club","mapping","env","confirm-production").containsAll(args.getOptionNames())
                || args.containsOption("dry-run") && !args.getOptionValues("dry-run").isEmpty()) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        var mappingPath=option(args,"mapping",null); var mapping=MappingConfig.load(mappingPath==null?null:Path.of(mappingPath));
        String slug=option(args,"club",mapping.defaultClub()); String env=option(args,"env","staging");
        if (!Set.of("staging","production").contains(env)) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        boolean production=env.equals("production") || environment.matchesProfiles("prod","production");
        if (production && !args.containsOption("dry-run") && !slug.equals(option(args,"confirm-production",null))) { throw new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION); }
        var report=importer.run(Path.of(args.getNonOptionArgs().getFirst()),mapping,slug,args.containsOption("dry-run"),production);
        System.out.print(report.render());
        if (report.hasErrors()) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
    }
    static String option(ApplicationArguments args,String key,String fallback) {
        if (!args.containsOption(key)) { return fallback; }
        var values=args.getOptionValues(key);
        if (values.size()!=1 || values.getFirst().isBlank()) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        return values.getFirst();
    }
}

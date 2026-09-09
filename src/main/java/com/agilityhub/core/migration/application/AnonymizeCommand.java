package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.domain.MappingConfig;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;

/** Runs without a Spring context: anonymization never connects to Mongo or loads account data. */
public final class AnonymizeCommand {
    private AnonymizeCommand() { }
    public static void run(ApplicationArguments args,String key) {
        if (args.getNonOptionArgs().size()!=2 || !Set.of("core.command","mapping").containsAll(args.getOptionNames())) {
            throw new IllegalArgumentException("Usage: migration:anonymize <dir> <out> [--mapping=file]; set MIGRATION_ANONYMIZE_KEY (32+ characters)");
        }
        var values=args.getOptionValues("mapping");
        if (values!=null && (values.size()!=1 || values.getFirst().isBlank())) { throw new IllegalArgumentException("Specify one mapping file"); }
        var mapping=MappingConfig.load(values==null ? null : Path.of(values.getFirst()));
        new PlayoffAnonymizer(key).anonymize(Path.of(args.getNonOptionArgs().getFirst()),Path.of(args.getNonOptionArgs().get(1)),mapping);
        System.out.println("Anonymized Playoff files written; no input values or key logged.");
    }
}

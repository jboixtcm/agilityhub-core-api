package com.agilityhub.core.identity.application;

import com.agilityhub.core.platform.application.definition.ClubDefinitions;
import com.agilityhub.core.shared.application.CoreCommand;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/** Alias of the accounts section; the target club must already exist. */
@Component
public class SeedTestAccountsCommand implements CoreCommand {
    private final ClubDefinitions definitions;
    public SeedTestAccountsCommand(ClubDefinitions definitions) { this.definitions = definitions; }
    @Override public String name() { return "identity:seed-test-accounts"; }
    @Override public void run(ApplicationArguments args) {
        var files = args.getNonOptionArgs();
        var slugs = args.getOptionValues("club");
        boolean bySlug = slugs != null && slugs.size() == 1 && slugs.getFirst().matches("[a-z0-9-]{3,40}") && files.isEmpty();
        boolean byFile = !args.containsOption("club") && files.size() == 1;
        if ((!bySlug && !byFile) || !Set.of("core.command", "club", "dry-run", "allow-seed-passwords").containsAll(args.getOptionNames())
                || Set.of("dry-run", "allow-seed-passwords").stream().anyMatch(option -> args.containsOption(option)
                    && !args.getOptionValues(option).isEmpty())) {
            throw new IllegalArgumentException("Usage: identity:seed-test-accounts <file> | --club=<slug> [--dry-run] [--allow-seed-passwords]");
        }
        Path file = bySlug ? Path.of("seeds", "club-" + slugs.getFirst() + ".yaml") : Path.of(files.getFirst());
        boolean dryRun = args.containsOption("dry-run");
        System.out.println(definitions.applyAccounts(file, dryRun, args.containsOption("allow-seed-passwords")).render(dryRun));
    }
}

package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.shared.application.CoreCommand;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public class ClubApplyCommand implements CoreCommand {
    private final ClubDefinitions definitions;
    public ClubApplyCommand(ClubDefinitions definitions) { this.definitions = definitions; }
    @Override public String name() { return "club:apply"; }
    @Override public void run(ApplicationArguments arguments) {
        if (arguments.getNonOptionArgs().size() != 1 || !Set.of("core.command", "dry-run").containsAll(arguments.getOptionNames())
                || (arguments.containsOption("dry-run") && !arguments.getOptionValues("dry-run").isEmpty())) {
            throw new IllegalArgumentException("Usage: club:apply <file> [--dry-run]");
        }
        boolean dryRun = arguments.containsOption("dry-run");
        System.out.println(definitions.apply(Path.of(arguments.getNonOptionArgs().getFirst()), dryRun).render(dryRun));
    }
}

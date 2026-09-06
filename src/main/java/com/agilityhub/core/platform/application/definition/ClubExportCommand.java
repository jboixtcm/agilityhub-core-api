package com.agilityhub.core.platform.application.definition;

import com.agilityhub.core.shared.application.CoreCommand;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public class ClubExportCommand implements CoreCommand {
    private final ClubDefinitions definitions;
    private final ClubDefinitionCodec codec;
    public ClubExportCommand(ClubDefinitions definitions, ClubDefinitionCodec codec) { this.definitions = definitions; this.codec = codec; }
    @Override public String name() { return "club:export"; }
    @Override public void run(ApplicationArguments arguments) {
        if (arguments.getNonOptionArgs().size() != 1 || !Set.of("core.command").containsAll(arguments.getOptionNames())) {
            throw new IllegalArgumentException("Usage: club:export <slug>");
        }
        System.out.print(codec.write(definitions.export(arguments.getNonOptionArgs().getFirst())));
    }
}

package com.agilityhub.core.cli;

import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.domain.ApiException;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public class CoreCli {
    private final List<CoreCommand> commands;
    public CoreCli(List<CoreCommand> commands) { this.commands = commands; }

    public int execute(ApplicationArguments arguments) {
        try {
            var names = arguments.getOptionValues("core.command");
            if (names == null || names.size() != 1 || names.getFirst().isBlank()) {
                throw new IllegalArgumentException("Specify exactly one --core.command=<name>");
            }
            var command = commands.stream().filter(candidate -> candidate.name().equals(names.getFirst())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown or unavailable command: " + names.getFirst()));
            command.run(arguments);
            return 0;
        } catch (ApiException failure) {
            System.err.println(failure.code() + " " + failure.details());
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
        } catch (RuntimeException failure) {
            // Persistence exceptions may contain private document values. Never print them.
            System.err.println("Command failed (" + failure.getClass().getSimpleName() + ")");
        }
        return 1;
    }
}

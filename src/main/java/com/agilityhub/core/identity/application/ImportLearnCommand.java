package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.domain.Email;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public class ImportLearnCommand implements CoreCommand {
    private final LearnImportService importer;
    private final ObjectMapper mapper;
    public ImportLearnCommand(LearnImportService importer, ObjectMapper mapper) { this.importer = importer; this.mapper = mapper; }
    @Override public String name() { return "identity:import-learn"; }
    @Override public void run(ApplicationArguments arguments) {
        if (arguments.getNonOptionArgs().size() != 1 || !Set.of("core.command", "dry-run", "platform-admins").containsAll(arguments.getOptionNames())
                || arguments.containsOption("dry-run") && !arguments.getOptionValues("dry-run").isEmpty()) { throw usage(); }
        Set<String> admins = Set.of();
        if (arguments.containsOption("platform-admins")) {
            var values = arguments.getOptionValues("platform-admins");
            if (values.size() != 1) { throw usage(); }
            admins = Arrays.stream(values.getFirst().split(",", -1)).map(Email::normalize).collect(java.util.stream.Collectors.toSet());
            if (admins.stream().anyMatch(email -> !LearnImportService.validEmail(email))) { throw usage(); }
        }
        var report = importer.importFile(Path.of(arguments.getNonOptionArgs().getFirst()), arguments.containsOption("dry-run"), admins);
        System.out.println(report.render());
        if (!report.dryRun()) {
            try {
                Files.createDirectories(Path.of("target"));
                mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/import-learn-report.json").toFile(), report);
            } catch (IOException failure) { throw new IllegalArgumentException("Could not write Learn import report"); }
        }
        if (report.errors() > 0) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
    }
    private IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: identity:import-learn <csv> [--dry-run] [--platform-admins=a@x,b@y]");
    }
}

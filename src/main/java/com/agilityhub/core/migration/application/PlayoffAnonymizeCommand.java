package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.domain.MappingConfig;
import com.agilityhub.core.shared.application.CoreCommand;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public class PlayoffAnonymizeCommand implements CoreCommand {
    private final String key;
    public PlayoffAnonymizeCommand(@Value("${core.migration.anonymization-key:}") String key) { this.key=key; }
    @Override public String name() { return "migration:anonymize"; }
    @Override public void run(ApplicationArguments args) {
        if (args.getNonOptionArgs().size()!=2 || !Set.of("core.command","mapping").containsAll(args.getOptionNames())) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        var path=PlayoffImportCommand.option(args,"mapping",null);
        new PlayoffAnonymizer(key).anonymize(Path.of(args.getNonOptionArgs().get(0)),Path.of(args.getNonOptionArgs().get(1)),MappingConfig.load(path==null?null:Path.of(path)));
        System.out.println("Playoff anonymization completed; derivative contains fictional values.");
    }
}

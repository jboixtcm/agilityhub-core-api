package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.DemoDataset;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.*;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Component
public class DemoSeedCommand implements CoreCommand {
    private final DemoSeedService service; private final ClubConfigService configs; private final ObjectMapper mapper;
    public DemoSeedCommand(DemoSeedService service, ClubConfigService configs, ObjectMapper mapper) {
        this.service = service; this.configs = configs; this.mapper = mapper;
    }
    @Override public String name() { return "seed:demo"; }
    @Override public void run(ApplicationArguments args) {
        if (!Set.of("core.command", "club", "seed").containsAll(args.getOptionNames()) || !args.getNonOptionArgs().isEmpty()
                || !args.containsOption("club") || args.getOptionValues("club").size() != 1
                || !args.getOptionValues("club").getFirst().matches("[a-z0-9-]{3,40}")
                || args.containsOption("seed") && (args.getOptionValues("seed").size() != 1 || !args.getOptionValues("seed").getFirst().matches("-?[0-9]{1,18}"))) {
            throw new IllegalArgumentException("Usage: seed:demo --club=<slug> [--seed=<number>]");
        }
        String slug = args.getOptionValues("club").getFirst();
        long seed = args.containsOption("seed") ? Long.parseLong(args.getOptionValues("seed").getFirst()) : 42;
        String id = configs.findClubIdBySlug(slug).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
        try (var input = Files.newInputStream(Path.of("seeds", "demo-" + slug + ".yaml")); var tenant = TenantContext.open(id)) {
            var options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
            var spec = mapper.convertValue(new Yaml(new SafeConstructor(options)).load(input), DemoDataset.Spec.class);
            System.out.println(service.apply(spec, seed).render());
        } catch (IOException failure) { throw new IllegalArgumentException("Unreadable demo seed specification", failure); }
    }
}

package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.DemoDataset;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Component
public class DemoSeedCommand implements CoreCommand {
    private static final String USAGE = "Usage: seed:demo --club=<slug> [--seed=<number>] [--week-start=<YYYY-MM-DD Monday>] [--reanchor]";
    private final DemoSeedService service; private final DemoPlanningService planning; private final ClubConfigService configs;
    private final ClubClock clubClock; private final ObjectMapper mapper;
    public DemoSeedCommand(DemoSeedService service, DemoPlanningService planning, ClubConfigService configs, ClubClock clubClock, ObjectMapper mapper) {
        this.service = service; this.planning = planning; this.configs = configs; this.clubClock = clubClock; this.mapper = mapper;
    }
    @Override public String name() { return "seed:demo"; }
    @Override public void run(ApplicationArguments args) {
        if (!Set.of("core.command", "club", "seed", "week-start", "reanchor").containsAll(args.getOptionNames()) || !args.getNonOptionArgs().isEmpty()
                || args.containsOption("reanchor") && !args.getOptionValues("reanchor").isEmpty()
                || !args.containsOption("club") || args.getOptionValues("club").size() != 1
                || !args.getOptionValues("club").getFirst().matches("[a-z0-9-]{3,40}")
                || args.containsOption("seed") && (args.getOptionValues("seed").size() != 1 || !args.getOptionValues("seed").getFirst().matches("-?[0-9]{1,18}"))
                || args.containsOption("week-start") && (args.getOptionValues("week-start").size() != 1 || !monday(args.getOptionValues("week-start").getFirst()))) {
            throw new IllegalArgumentException(USAGE);
        }
        String slug = args.getOptionValues("club").getFirst();
        long seed = args.containsOption("seed") ? Long.parseLong(args.getOptionValues("seed").getFirst()) : 42;
        String id = configs.findClubIdBySlug(slug).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND));
        try (var input = Files.newInputStream(Path.of("seeds", "demo-" + slug + ".yaml")); var tenant = TenantContext.open(id)) {
            var options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
            Map<String, Object> document = new LinkedHashMap<>(mapper.convertValue(new Yaml(new SafeConstructor(options)).load(input), new TypeReference<Map<String, Object>>() { }));
            var sections = new LinkedHashMap<String, Object>();
            for (String section : DemoPlanningService.SECTIONS) { if (document.containsKey(section)) { sections.put(section, document.remove(section)); } }
            var spec = mapper.convertValue(document, DemoDataset.Spec.class);
            System.out.println(service.apply(spec, seed).render());
            if (!sections.isEmpty()) {
                boolean reanchor = args.containsOption("reanchor");
                var weekStart = args.containsOption("week-start") ? LocalDate.parse(args.getOptionValues("week-start").getFirst())
                        : defaultWeekStart(clubClock.today(id), reanchor);
                System.out.println(planning.apply(spec, sections, seed, weekStart, reanchor).render());
            }
        } catch (IOException failure) { throw new IllegalArgumentException("Unreadable demo seed specification", failure); }
    }
    /**
     * The anchor without `--week-start` (E6-T04 round 2, review #5): the first club-local Monday on or after the run date,
     * so the `scenario` section (E5 and the E6 fixture of R-10-02's example, applied only while its week is still ahead)
     * is seeded whatever the day of the run. `--reanchor` keeps E5-T09's anchor, the Monday of the current week, whose
     * planning weeks it refreshes.
     */
    static LocalDate defaultWeekStart(LocalDate today, boolean reanchor) {
        return today.with(reanchor ? TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY) : TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }
    private static boolean monday(String value) {
        try { return LocalDate.parse(value).getDayOfWeek() == DayOfWeek.MONDAY; } catch (DateTimeException invalid) { return false; }
    }
}

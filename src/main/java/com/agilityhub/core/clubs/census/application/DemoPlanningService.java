package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.DemoDataset;
import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.core.env.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E4-T05: dated demo planning and activities on top of the census demo, contributed by {@link DemoSeedStep}s in one
 * transaction. The first completed run is recorded per club; later runs (any week start) change nothing. E5-T09: a
 * `--reanchor` run re-applies the planning weeks (and their registrants) to a new week start on a long-lived stack; it is
 * recorded per week start, so it is idempotent per run date.
 */
@Service
public class DemoPlanningService {
    public static final List<String> SECTIONS = List.of("planning", "bookings", "activities", "scenario");
    public record Result(String id, int changes, Map<String, Integer> counts, LocalDate weekStart) {
        public String render() { return counts + "\n" + changes + " changes (demo planning, week start " + weekStart + ")"; }
    }
    private final CensusAccess census; private final DemoSeedRepository runs; private final List<DemoSeedStep> steps;
    private final ObjectMapper mapper; private final Environment environment; private final ClubClock clubClock;
    public DemoPlanningService(CensusAccess census, DemoSeedRepository runs, List<DemoSeedStep> steps, ObjectMapper mapper, Environment environment,
            ClubClock clubClock) {
        this.census = census; this.runs = runs; this.steps = steps.stream().sorted(Comparator.comparingInt(DemoSeedStep::order)).toList();
        this.mapper = mapper; this.environment = environment; this.clubClock = clubClock;
    }
    @Transactional
    public Result apply(DemoDataset.Spec spec, Map<String, Object> specification, long seed, LocalDate weekStart) {
        return apply(spec, specification, seed, weekStart, false);
    }
    /** `reanchor` = E5-T09 `seed:demo --reanchor`: needs the first run, then applies the reanchoring steps once per week start. */
    @Transactional
    public Result apply(DemoDataset.Spec spec, Map<String, Object> specification, long seed, LocalDate weekStart, boolean reanchor) {
        if (!environment.acceptsProfiles(Profiles.of("local", "test")) || environment.acceptsProfiles(Profiles.of("staging", "prod"))) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "weekStart")); }
        String club = TenantContext.require(); String first = club + ":planning"; String signature = mapper.valueToTree(specification).toString();
        var previous = runs.findById(first).orElse(null);
        if (previous != null && (previous.seed() != seed || !previous.specification().equals(signature))) { throw new ApiException(ErrorCode.CLUB_NOT_EMPTY); }
        if (reanchor && previous == null) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("demoSeed", "planning")); }
        if (previous != null) {
            if (!reanchor || weekStart.toString().equals(previous.weekStart())) { return new Result(first, 0, previous.counts(), LocalDate.parse(previous.weekStart())); }
            var anchored = runs.findById(first + ":" + weekStart).orElse(null);
            if (anchored != null) { return new Result(anchored.id(), 0, anchored.counts(), weekStart); }
        }
        if (runs.findById(club).isEmpty()) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("demoSeed", "census")); }
        var members = DemoDataset.generate(spec, seed, club).members();
        String admin = census.members.require(members.get(spec.administrators().getFirst()).id()).accountId;
        var logins = new HashSet<String>(); members.subList(0, spec.accountEmails().size()).forEach(m -> logins.add(m.id()));
        var input = new DemoSeedStep.Input(specification, seed, weekStart, admin, logins, members.stream().map(DemoDataset.MemberRow::id).toList(),
                clubClock.today(club), reanchor);
        var counts = new LinkedHashMap<String, Integer>();
        for (var step : steps) {
            if (reanchor && !step.reanchors()) { continue; }
            counts.putAll(DemoSeedActor.as(admin, "ADMIN", () -> step.apply(input)));
        }
        String id = reanchor ? first + ":" + weekStart : first;
        runs.insert(new DemoSeedRun(id, club, seed, signature, counts, weekStart.toString()));
        return new Result(id, counts.values().stream().mapToInt(Integer::intValue).sum(), counts, weekStart);
    }
}

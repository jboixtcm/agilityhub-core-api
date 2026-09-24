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
 * transaction. The first completed run is recorded per club; later runs (any week start) change nothing.
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
        if (!environment.acceptsProfiles(Profiles.of("local", "test")) || environment.acceptsProfiles(Profiles.of("staging", "prod"))) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "weekStart")); }
        String club = TenantContext.require(); String id = club + ":planning"; String signature = mapper.valueToTree(specification).toString();
        var previous = runs.findById(id).orElse(null);
        if (previous != null) {
            if (previous.seed() != seed || !previous.specification().equals(signature)) { throw new ApiException(ErrorCode.CLUB_NOT_EMPTY); }
            return new Result(id, 0, previous.counts(), LocalDate.parse(previous.weekStart()));
        }
        if (runs.findById(club).isEmpty()) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("demoSeed", "census")); }
        var members = DemoDataset.generate(spec, seed, club).members();
        String admin = census.members.require(members.get(spec.administrators().getFirst()).id()).accountId;
        var logins = new HashSet<String>(); members.subList(0, spec.accountEmails().size()).forEach(m -> logins.add(m.id()));
        var input = new DemoSeedStep.Input(specification, seed, weekStart, admin, logins, members.stream().map(DemoDataset.MemberRow::id).toList(),
                clubClock.today(club));
        var counts = new LinkedHashMap<String, Integer>();
        for (var step : steps) { counts.putAll(DemoSeedActor.as(admin, "ADMIN", () -> step.apply(input))); }
        runs.insert(new DemoSeedRun(id, club, seed, signature, counts, weekStart.toString()));
        return new Result(id, counts.values().stream().mapToInt(Integer::intValue).sum(), counts, weekStart);
    }
}

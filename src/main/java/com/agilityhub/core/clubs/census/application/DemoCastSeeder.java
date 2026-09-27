package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.catalogs.application.RoleAssignmentService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

/**
 * E6-T04 demo cast of S10 WP-10-G (`scenario.attendance`, applied with {@link DemoSeedStep.Input#scenario()}), before the
 * S08 step books it: the invented names the mockups use, given through the admin's S03/S05 edits — the class instructor's
 * short name (S05) and member first name, each cast member's first name, their dog's name and handler (R-10-00 «{guia} +
 * {gos}»), and, for a login member whose dogs are of other levels (the seed logins own CAD and E dogs), the dog's level
 * change first (`dogLevel` → `level`, S03; `DogLevelChanged`). Then the holder's own note to the instructors (S03 R-03-17,
 * as the member: `MemberNoteChanged` feeds D14 and N-22).
 */
@Service
public class DemoCastSeeder implements DemoSeedStep {
    public record Staff(int index, String name) { }
    public record Cast(String name, int member, String dog, String dogLevel, String level) { }
    public record Note(String by, String text) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(Staff instructor, List<Cast> cast, Note memberNote) {
        public Spec { cast = cast == null ? List.of() : List.copyOf(cast); }
    }
    private final CensusAccess census; private final CensusQuery query; private final MemberService members; private final DogService dogs;
    private final PlanningCatalogAccess catalogs; private final RoleAssignmentService team; private final ObjectMapper mapper;
    public DemoCastSeeder(CensusAccess census, CensusQuery query, MemberService members, DogService dogs, PlanningCatalogAccess catalogs,
            RoleAssignmentService team, ObjectMapper mapper) {
        this.census = census; this.query = query; this.members = members; this.dogs = dogs; this.catalogs = catalogs; this.team = team; this.mapper = mapper;
    }
    @Override public int order() { return 36; }
    @Override public Map<String, Integer> apply(Input input) {
        var counts = new LinkedHashMap<String, Integer>(); counts.put("attendanceRenamed", 0); counts.put("attendanceLevelChanges", 0); counts.put("followupNotes", 0);
        var section = input.scenario().get("attendance");
        if (section == null) { return counts; }
        var spec = mapper.convertValue(section, Spec.class);
        var levels = catalogs.levelIdsByCode();
        if (spec.instructor() != null) {
            var ids = catalogs.activeInstructorIds();
            if (spec.instructor().index() < 0 || spec.instructor().index() >= ids.size()) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("instructor", spec.instructor().index())); }
            String id = ids.get(spec.instructor().index());
            team.renameInstructor(id, spec.instructor().name());
            rename(catalogs.instructorMembers(List.of(id)).getFirst(), spec.instructor().name()); counts.merge("attendanceRenamed", 1, Integer::sum);
        }
        var dogIds = new HashMap<String, String>();
        for (var c : spec.cast()) {
            String memberId = input.member(c.member());
            String dogId = dog(memberId, level(levels, c.dogLevel() == null ? c.level() : c.dogLevel()));
            if (c.dogLevel() != null && !c.dogLevel().equals(c.level())) {
                dogs.level(dogId, level(levels, c.level())); counts.merge("attendanceLevelChanges", 1, Integer::sum);
            }
            rename(memberId, c.name());
            var dog = query.dog(dogId);
            var patch = new LinkedHashMap<String, Object>(); patch.put("name", c.dog()); patch.put("handlerName", c.name()); patch.put("version", dog.get("version"));
            dogs.patch(dogId, patch);
            dogIds.put(c.name(), dogId); counts.merge("attendanceRenamed", 1, Integer::sum);
        }
        if (spec.memberNote() != null) {
            var holder = spec.cast().stream().filter(c -> c.name().equals(spec.memberNote().by())).findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("demoCast", spec.memberNote().by())));
            String account = census.members.require(input.member(holder.member())).accountId;
            DemoSeedActor.as(account, "MEMBER", () -> { dogs.note(dogIds.get(holder.name()), spec.memberNote().text()); return null; });
            counts.merge("followupNotes", 1, Integer::sum);
        }
        return counts;
    }
    private static String level(Map<String, String> levels, String code) {
        var id = levels.get(code); if (id == null) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("reference", code)); } return id;
    }
    /** The member's first ACTIVE dog (by id) of a level. */
    private String dog(String memberId, String levelId) {
        return census.dogs.matching(Criteria.where("memberId").is(memberId).and("status").is("ACTIVE").and("levelId").is(levelId)).stream()
                .map(dog -> dog.id).sorted().findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("demoMember", memberId, "levelId", levelId)));
    }
    private void rename(String memberId, String firstName) {
        var member = query.member(memberId, true);
        if (firstName.equals(member.get("firstName"))) { return; }
        var patch = new LinkedHashMap<String, Object>(); patch.put("firstName", firstName); patch.put("version", member.get("version"));
        members.patch(memberId, patch, false);
    }
}

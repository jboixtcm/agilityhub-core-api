package com.agilityhub.core.courses.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.courses.domain.AgilityHubLevel;
import com.agilityhub.core.courses.domain.CourseParts.*;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.domain.ObstacleType;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.DemoSeedStep;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * E9-T05 step 1: the `courses` section of a demo seed (S16; WP-16-G: test data only, no Supabase import, decision A6 of 30-09), written
 * through the S16 application services in the demo planning transaction: the club's obstacle inventory, ring geometries with six
 * markers (the Unity export's A–F), club courses from fictional Smarter models, placements, ring setups and one finished build
 * session, whose `finish` registers its ring's setup (R-16-11). The planning run that calls this step is recorded once, so a second
 * `seed:demo` changes nothing (DemoPlanningService). A club without `COURSES` gets nothing.
 * <p>
 * The seed plays course-core's part (R-16-01): it writes the course-core v1 `CourseData` of each model from compact rows
 * `[type, x, y, rotation]` (metres from the bottom-left corner, numbered in order) and takes `pathLengthM` from the file. Its
 * placements are translations (the file has `offsetXM` and `offsetYM` only), whose resolved obstacles are the model's plus the
 * offset, as course-core resolves a placement without rotation or mirror.
 */
@Service
public class DemoCoursesSeeder implements DemoSeedStep {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CourseSpec(String key, Map<String, String> name, Discipline discipline, List<String> levels, AgilityHubLevel agilityhubLevel,
            double designLengthM, double designWidthM, Double pathLengthM, String designer, List<String> tags, List<List<Object>> obstacles) {
        public CourseSpec {
            levels = levels == null ? List.of() : List.copyOf(levels); tags = tags == null ? List.of() : List.copyOf(tags);
            obstacles = obstacles == null ? List.of() : List.copyOf(obstacles);
        }
    }
    public record PlacementSpec(String key, String course, String ring, double offsetXM, double offsetYM, String name) { }
    public record SetupSpec(String ring, SetupKind kind, String placement, String course, AgilityHubLevel agilityhubLevel, List<String> levels,
            Integer expectedInDays, String notes) {
        public SetupSpec { levels = levels == null ? List.of() : List.copyOf(levels); }
    }
    public record BuildSessionSpec(String placement) { }
    /** `geometries`: `PUT /rings/{id}/geometry` bodies plus the ring's `ring` name; the rest as `POST /courses`, `/placements`, `/ring-setups`. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(List<InventoryItem> inventory, List<Map<String, Object>> geometries, List<CourseSpec> courses, List<PlacementSpec> placements,
            List<SetupSpec> setups, BuildSessionSpec buildSession) {
        public Spec {
            inventory = inventory == null ? List.of() : List.copyOf(inventory); geometries = geometries == null ? List.of() : List.copyOf(geometries);
            courses = courses == null ? List.of() : List.copyOf(courses); placements = placements == null ? List.of() : List.copyOf(placements);
            setups = setups == null ? List.of() : List.copyOf(setups);
        }
    }
    private final ObjectMapper mapper; private final ClubConfigService configs; private final PlanningCatalogAccess catalogs;
    private final ObstacleInventoryService inventories; private final RingGeometryService geometries; private final CourseLibrary library;
    private final PlacementService placements; private final RingSetupService setups; private final BuildSessionService sessions;
    public DemoCoursesSeeder(ObjectMapper mapper, ClubConfigService configs, PlanningCatalogAccess catalogs, ObstacleInventoryService inventories,
            RingGeometryService geometries, CourseLibrary library, PlacementService placements, RingSetupService setups, BuildSessionService sessions) {
        this.mapper = mapper; this.configs = configs; this.catalogs = catalogs; this.inventories = inventories; this.geometries = geometries;
        this.library = library; this.placements = placements; this.setups = setups; this.sessions = sessions;
    }
    @Override public int order() { return 55; }
    @Override public Map<String, Integer> apply(Input input) {
        var section = input.specification().get("courses");
        if (section == null || !configs.get(TenantContext.require()).modules().contains(Module.COURSES)) { return Map.of(); }
        var spec = mapper.convertValue(section, Spec.class);
        var counts = new LinkedHashMap<String, Integer>();
        if (!spec.inventory().isEmpty()) { inventories.put("club", spec.inventory()); counts.put("obstacleInventories", 1); }
        for (var body : spec.geometries()) {
            var fields = new LinkedHashMap<>(body); String ring = ring(String.valueOf(fields.remove("ring")));
            geometries.put(ring, mapper.convertValue(fields, RingGeometryService.GeometryInput.class), 0);
            counts.merge("ringGeometries", 1, Integer::sum);
        }
        var courses = new HashMap<String, Seeded>();
        // R-16-17: a club course names the club's languages; the seed's other texts are kept for a club that has them.
        var locales = configs.get(TenantContext.require()).club().locales();
        for (var course : spec.courses()) {
            var name = new LinkedHashMap<>(course.name()); name.keySet().retainAll(locales);
            var created = library.create(new CourseLibrary.CourseInput(name, course.discipline(), CourseSource.SMARTER, model(course, input), 1,
                    null, null, null, course.designer(), input.today(), null, course.agilityhubLevel(), levels(course.levels()), course.tags(), null,
                    Visibility.CLUB, null, course.pathLengthM()));
            courses.put(course.key(), new Seeded(created.course().id(), created.course().version(), course));
            counts.merge("courses", 1, Integer::sum);
        }
        var placed = new HashMap<String, String>();
        for (var placement : spec.placements()) {
            var course = require(courses, placement.course());
            var created = placements.create(new PlacementService.PlacementInput(course.id(), course.version(), ring(placement.ring()),
                    new PlacementTransform(placement.offsetXM(), placement.offsetYM(), 0.0, false, false), PlacementMode.PRESERVE_METERS, null,
                    resolved(course.spec(), placement), List.of(), null, placement.name(), false, PlacementStatus.READY_TO_BUILD, PlacementUse.TRAINING,
                    null, List.of(), List.of(), null));
            placed.put(placement.key(), created.placement().id());
            counts.merge("placements", 1, Integer::sum);
        }
        for (var setup : spec.setups()) {
            setups.register(new RingSetupService.SetupInput(ring(setup.ring()), setup.kind(), setup.placement() == null ? null : require(placed, setup.placement()),
                    setup.course() == null ? null : require(courses, setup.course()).id(), null, setup.agilityhubLevel(), levels(setup.levels()),
                    setup.expectedInDays() == null ? null : input.today().plusDays(setup.expectedInDays()), setup.notes()));
            counts.merge("ringSetups", 1, Integer::sum);
        }
        if (spec.buildSession() != null) {
            // R-16-11: started, every obstacle placed, finished; `finish` registers its ring's setup.
            var session = sessions.start(require(placed, spec.buildSession().placement()), false, null);
            session.obstacles().forEach(obstacle -> sessions.mark(session.id(), obstacle.obstacleId(), ObstacleStatus.PLACED));
            sessions.finish(session.id());
            counts.put("buildSessions", 1); counts.merge("ringSetups", 1, Integer::sum);
        }
        return counts;
    }

    /** A seeded course: its id and version, and the rows its placements resolve. */
    private record Seeded(String id, long version, CourseSpec spec) { }

    /** The course-core v1 `CourseData` of {@code course}: one obstacle and one number per row, in order (schema-checked on create). */
    ObjectNode model(CourseSpec course, Input input) {
        var data = mapper.createObjectNode();
        String title = course.name().getOrDefault("ca", course.name().values().iterator().next());
        data.put("id", "demo-" + course.key()).put("source", "smarter-agility").put("sourceFileName", course.key() + ".txt").put("title", title).put("titleRaw", title)
                .put("units", "M").put("designLengthMeters", course.designLengthM()).put("designWidthMeters", course.designWidthM())
                .put("canvasWidth", course.designLengthM() * 20).put("canvasHeight", course.designWidthM() * 20).put("origin", "LB");
        data.putObject("metadata").put("type", "").put("courseType", course.discipline() == Discipline.JUMPING ? "Jumping" : "Agility").put("grade", "")
                .put("category", "").put("location", "").put("designer", course.designer() == null ? "" : course.designer()).put("organization", "")
                .put("date", input.today().toString()).putNull("exportVersion").putNull("obstacleSummary");
        var obstacles = data.putArray("obstacles"); var numbers = data.putArray("numbers");
        for (int i = 0; i < course.obstacles().size(); i++) {
            var row = row(course, i); String source = String.valueOf(i + 1), type = JSON.convertValue(row.type(), String.class);
            obstacles.addObject().put("id", "obstacle-" + i).put("sourceId", source).put("sourceCode", type.toLowerCase(Locale.ROOT))
                    .put("obstacleType", type).put("xMeters", row.x()).put("yMeters", row.y()).put("rotationDegrees", row.rotation())
                    .putNull("nominalLengthMeters").putNull("controlPointsMeters").putNull("colorKey").putObject("rawMetadata");
            numbers.addObject().put("id", "number-" + i).put("text", source).put("sequenceNumber", i + 1).put("xMeters", row.x() - 1).put("yMeters", row.y())
                    .put("linkedObstacleSourceId", source).putNull("obstacleConnectionPoint").putObject("rawMetadata");
        }
        data.putArray("noGoZones"); data.putArray("ignoredLogos"); data.putObject("rawMetadata").put("demo", true);
        return data;
    }
    /** The placement's resolved obstacles: the model's moved by the offset, each with its number (no rotation, no mirror). */
    static List<ResolvedObstacle> resolved(CourseSpec course, PlacementSpec placement) {
        var out = new ArrayList<ResolvedObstacle>();
        for (int i = 0; i < course.obstacles().size(); i++) {
            var row = row(course, i);
            out.add(new ResolvedObstacle("obstacle-" + i, row.type(), row.x() + placement.offsetXM(), row.y() + placement.offsetYM(), row.rotation(), List.of(i + 1), List.of()));
        }
        return out;
    }
    /** A model row; its type is course-core's spelling (`Jump`, `Tunnel4m` …), as the schema and the API write it. */
    private record Row(ObstacleType type, double x, double y, double rotation) { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Row row(CourseSpec course, int index) {
        var values = course.obstacles().get(index);
        if (values.size() != 4) { throw new IllegalArgumentException("Demo course " + course.key() + " obstacle " + (index + 1) + ": [type, x, y, rotation]"); }
        return new Row(JSON.convertValue(values.get(0), ObstacleType.class), number(values.get(1)), number(values.get(2)), number(values.get(3)));
    }
    private static double number(Object value) { return ((Number) value).doubleValue(); }
    private String ring(String name) {
        return catalogs.rings().stream().filter(r -> r.name().equals(name)).map(PlanningCatalogAccess.RingView::id).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("demoRing", name)));
    }
    private List<String> levels(List<String> codes) {
        var refs = catalogs.levelRefs().values();
        return codes.stream().map(code -> refs.stream().filter(ref -> ref.code().equals(code)).map(PlanningCatalogAccess.LevelRef::id).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("demoLevel", code)))).toList();
    }
    private static <T> T require(Map<String, T> values, String key) {
        var value = values.get(key);
        if (value == null) { throw new ApiException(ErrorCode.NOT_FOUND, Map.of("demoCourses", key)); }
        return value;
    }
}

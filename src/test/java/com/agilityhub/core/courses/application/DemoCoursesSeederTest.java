package com.agilityhub.core.courses.application;

import com.agilityhub.core.courses.domain.RingGeometryRules;
import com.agilityhub.core.shared.application.DemoSeedStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import static org.assertj.core.api.Assertions.*;

/**
 * E9-T05 step 1: the `courses` section of `seeds/demo-canic.yaml` read with a strict mapper (an unknown key fails, where the
 * application's lenient one would ignore it): every model is a course-core v1 `CourseData` the committed schema accepts, every
 * geometry passes R-16-04 with the six markers the Unity export needs, the placements resolve to the model plus their offset, and
 * every reference between the rows names a row of the file.
 */
class DemoCoursesSeederTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DemoCoursesSeeder seeder = new DemoCoursesSeeder(mapper, null, null, null, null, null, null, null, null);

    Map<String, Object> seedFile() throws Exception {
        try (var input = Files.newInputStream(Path.of("seeds", "demo-canic.yaml"))) {
            return mapper.convertValue(new Yaml(new SafeConstructor(new LoaderOptions())).load(input), new TypeReference<Map<String, Object>>() { });
        }
    }
    DemoSeedStep.Input input(Map<String, Object> specification) {
        return new DemoSeedStep.Input(specification, 42, LocalDate.parse("2026-09-14"), "demo-admin", Set.of(), List.of(), LocalDate.parse("2026-09-09"), false);
    }

    @Test void T_16_03_T_16_06_theDemoCoursesAreValidModelsGeometriesAndPlacements() throws Exception {
        var file = seedFile();
        var spec = mapper.convertValue(file.get("courses"), DemoCoursesSeeder.Spec.class);
        assertThat(spec.courses()).extracting(DemoCoursesSeeder.CourseSpec::key).containsExactly("jumping-cd", "agility-ab", "tunnels");
        var schemas = new CourseSchemas(mapper);
        for (var course : spec.courses()) {
            var model = seeder.model(course, input(file));
            schemas.validateCourseData(1, model);
            assertThat(model.path("obstacles")).hasSize(course.obstacles().size());
            assertThat(model.at("/numbers/0/linkedObstacleSourceId").asText()).isEqualTo(model.at("/obstacles/0/sourceId").asText());
            assertThat(model.at("/metadata/date").asText()).isEqualTo("2026-09-09");
            assertThat(course.pathLengthM()).isPositive();
        }
        for (var body : spec.geometries()) {
            var fields = new LinkedHashMap<>(body); fields.remove("ring");
            var geometry = mapper.convertValue(fields, RingGeometryService.GeometryInput.class);
            assertThat(RingGeometryRules.check(geometry.lengthM(), geometry.widthM(), geometry.borderClearanceM(), geometry.doors(), geometry.noGoZones(),
                    geometry.markers())).as(body.get("ring").toString()).isEmpty();
            assertThat(geometry.markers()).hasSize(BuildSessionExport.MARKERS).extracting(m -> m.label()).containsExactly("A", "B", "C", "D", "E", "F");
        }
        var courses = new HashMap<String, DemoCoursesSeeder.CourseSpec>(); spec.courses().forEach(c -> courses.put(c.key(), c));
        var placements = new HashSet<String>();
        for (var placement : spec.placements()) {
            var course = courses.get(placement.course());
            assertThat(course).as(placement.course()).isNotNull();
            var resolved = DemoCoursesSeeder.resolved(course, placement);
            assertThat(resolved).hasSize(course.obstacles().size());
            var first = course.obstacles().getFirst();
            assertThat(resolved.getFirst().x()).isEqualTo(((Number) first.get(1)).doubleValue() + placement.offsetXM());
            assertThat(resolved.getFirst().y()).isEqualTo(((Number) first.get(2)).doubleValue() + placement.offsetYM());
            assertThat(resolved.getFirst().numbers()).containsExactly(1);
            placements.add(placement.key());
        }
        for (var setup : spec.setups()) {
            assertThat(setup.placement() == null || placements.contains(setup.placement())).as(setup.ring()).isTrue();
            assertThat(setup.course() == null || courses.containsKey(setup.course())).as(setup.ring()).isTrue();
        }
        assertThat(placements).contains(spec.buildSession().placement());
    }

    @Test void aSeedWithoutACoursesSectionWritesNothing() {
        assertThat(seeder.apply(input(Map.of("planning", Map.of())))).isEmpty();
        assertThat(seeder.order()).isEqualTo(55);
    }

    @Test void aModelRowIsTypeXYAndRotation() {
        var course = new DemoCoursesSeeder.CourseSpec("broken", Map.of("ca", "Trencat"), null, null, null, 10, 10, null, null, null,
                List.of(List.of("Jump", 1, 2)));
        assertThatThrownBy(() -> seeder.model(course, input(Map.of()))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[type, x, y, rotation]");
    }
}

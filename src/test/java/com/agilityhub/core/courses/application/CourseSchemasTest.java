package com.agilityhub.core.courses.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CourseSchemasTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CourseSchemas schemas = new CourseSchemas(mapper);
    CourseSchemasTest() throws Exception { }
    ObjectNode fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/courses/" + name + "-valid.json")) { return (ObjectNode) mapper.readTree(input); }
    }
    @Test void T_16_03_courseDataAndUnityExportUseTheCommittedDraftSevenSchemas() throws Exception {
        schemas.validateCourseData(1, fixture("course-data"));
        try { schemas.validateExport(fixture("build-session-export")); } catch (ApiException ex) { throw new AssertionError(ex.details().toString(), ex); }
    }
    @Test void T_16_03_requiredFieldsUnknownObstaclesAndUnknownKeysFailWithStructuredDetails() throws Exception {
        var absent = fixture("course-data"); absent.remove("title");
        var obstacle = fixture("course-data"); ((ObjectNode) obstacle.path("obstacles").get(0)).put("obstacleType", "Unlisted");
        var unknown = fixture("course-data").put("unlisted", true);
        for (var value : List.of(absent, obstacle, unknown, mapper.nullNode())) {
            assertThatThrownBy(() -> schemas.validateCourseData(1, value)).isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.code()).isEqualTo(ErrorCode.COURSE_MODEL_INVALID);
                assertThat(ex.details().keySet()).containsExactly("schemaErrors");
                var errors = mapper.valueToTree(ex.details()).path("schemaErrors");
                assertThat(errors).isNotEmpty(); errors.forEach(error -> assertThat(error.fieldNames()).toIterable().containsExactlyInAnyOrder("path", "message"));
            });
        }
    }
    @Test void T_16_03_versionsAndByteLimitAreEnforcedWithoutFetchingSchemaIds() throws Exception {
        for (Integer version : java.util.Arrays.asList(2, 0, null)) {
            assertThatThrownBy(() -> schemas.validateCourseData(version, fixture("course-data")))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.SCHEMA_VERSION_UNSUPPORTED));
        }
        assertThatThrownBy(() -> schemas.validateCourseData(1, null)).isInstanceOf(ApiException.class);
        var large = fixture("course-data"); ((ObjectNode) large.path("rawMetadata")).put("large", "à".repeat(140000));
        assertThatThrownBy(() -> schemas.validateCourseData(1, large)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.COURSE_MODEL_INVALID));
        assertThatThrownBy(() -> schemas.validateExport(fixture("build-session-export").put("schemaVersion", 2))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> schemas.validateExport(null)).isInstanceOf(ApiException.class);
    }
}

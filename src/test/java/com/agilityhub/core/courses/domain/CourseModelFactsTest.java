package com.agilityhub.core.courses.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** E9-T02: S16 §14.2's promoted fields and §3's stats, read from a validated CourseData. */
class CourseModelFactsTest {
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void T_16_03_theDesignSizeMetadataAndStatsComeFromTheModel() throws Exception {
        var model = (ObjectNode) MAPPER.readTree("""
                {"designLengthMeters": 40, "designWidthMeters": 20, "canvasWidth": 800, "canvasHeight": 400, "origin": "LB",
                 "metadata": {"grade": "2", "courseType": "Jumping"},
                 "obstacles": [{"id": "o1", "obstacleType": "Jump"}, {"id": "o2", "obstacleType": "Unknown"}, {"id": "o3", "obstacleType": "Tire"}],
                 "numbers": [
                   {"sequenceNumber": 2, "sequenceKey": "n1", "xMeters": 3, "yMeters": 4},
                   {"sequenceNumber": 1, "xMeters": 0, "yMeters": 0},
                   {"sequenceNumber": 3, "sequenceKey": "n1", "xMeters": 3, "yMeters": 5.333},
                   {"sequenceNumber": 1, "sequenceKey": "n2", "xMeters": 30, "yMeters": 15},
                   {"sequenceNumber": null, "xMeters": 20, "yMeters": 10}]}
                """);
        var facts = CourseModelFacts.of(model);
        assertThat(facts.designLengthM()).isEqualTo(40);
        assertThat(facts.designWidthM()).isEqualTo(20);
        assertThat(facts.canvasWidth()).isEqualTo(800);
        assertThat(facts.canvasHeight()).isEqualTo(400);
        assertThat(facts.origin()).isEqualTo("LB");
        assertThat(facts.courseGrade()).isEqualTo("2");
        assertThat(facts.courseType()).isEqualTo("Jumping");
        assertThat(facts.stats().obstacleCount()).isEqualTo(3);
        assertThat(facts.stats().bbox().w()).isEqualTo(40);
        assertThat(facts.stats().bbox().h()).isEqualTo(20);
        // The main sequence only, in order: (0, 0) → (3, 4) → (3, 5.333) = 5 + 1.333, to the centimetre.
        assertThat(facts.stats().pathLengthM()).isEqualTo(6.33);
        assertThat(facts.unknownObstacleIds()).containsExactly("o2");
    }

    @Test void T_16_03_withoutAModelEveryFactIsEmpty() throws Exception {
        for (var model : new com.fasterxml.jackson.databind.JsonNode[] {null, MAPPER.nullNode(), MAPPER.readTree("[]")}) {
            var facts = CourseModelFacts.of(model);
            assertThat(facts.designLengthM()).isNull();
            assertThat(facts.origin()).isNull();
            assertThat(facts.stats().obstacleCount()).isZero();
            assertThat(facts.stats().pathLengthM()).isZero();
            assertThat(facts.unknownObstacleIds()).isEmpty();
        }
        var sparse = CourseModelFacts.of(MAPPER.readTree("{\"origin\": 3, \"metadata\": {}}"));
        assertThat(sparse.origin()).isNull();
        assertThat(sparse.courseGrade()).isNull();
        assertThat(sparse.stats().pathLengthM()).isZero();
    }
}

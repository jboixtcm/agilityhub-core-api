package com.agilityhub.core.courses.domain;

import com.agilityhub.core.courses.domain.CourseParts.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * The fields S16 §14.2 promotes from a validated `CourseData` (`normalizedJson`) to the `Course`, and its `stats` (§3, «calculat
 * del model, per a llistats i filtres»). Read, never recomputed (R-16-01: the back does not reimplement geometry): the design size is
 * course-core's, the obstacle count is the length of its list, and `pathLengthM` is the value course-core's summary sent with the
 * model (E115; `CourseData` has no field for it). Without a model (an `IMAGE` course) every value is empty and the stats are zero.
 */
public record CourseModelFacts(Double designLengthM, Double designWidthM, Double canvasWidth, Double canvasHeight, String origin,
        String courseGrade, String courseType, CourseStats stats, List<String> unknownObstacleIds) {
    static final String UNKNOWN_TYPE = "Unknown";

    /** {@code pathLengthM}: course-core's path length of this model, or null when the client did not report it (stored as 0). */
    public static CourseModelFacts of(JsonNode model, Double pathLengthM) {
        if (model == null || model.isNull() || !model.isObject()) {
            return new CourseModelFacts(null, null, null, null, null, null, null, new CourseStats(0, 0, new CourseBbox(0, 0)), List.of());
        }
        double length = model.path("designLengthMeters").asDouble(), width = model.path("designWidthMeters").asDouble();
        var unknown = new ArrayList<String>();
        model.path("obstacles").forEach(obstacle -> {
            if (UNKNOWN_TYPE.equals(obstacle.path("obstacleType").asText())) { unknown.add(obstacle.path("id").asText()); }
        });
        var metadata = model.path("metadata");
        return new CourseModelFacts(length, width, model.path("canvasWidth").asDouble(), model.path("canvasHeight").asDouble(),
                text(model.path("origin")), text(metadata.path("grade")), text(metadata.path("courseType")),
                new CourseStats(model.path("obstacles").size(), pathLengthM == null ? 0 : pathLengthM, new CourseBbox(length, width)), List.copyOf(unknown));
    }

    private static String text(JsonNode node) { return node.isTextual() ? node.textValue() : null; }
}

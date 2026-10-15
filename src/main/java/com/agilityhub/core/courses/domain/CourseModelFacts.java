package com.agilityhub.core.courses.domain;

import com.agilityhub.core.courses.domain.CourseParts.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * The fields S16 §14.2 promotes from a validated `CourseData` (`normalizedJson`) to the `Course`, and its `stats` (§3, «calculat
 * del model, per a llistats i filtres»). Read, never recomputed: the design size is course-core's, the obstacle count is the length
 * of its list, and `pathLengthM` joins the numbered labels of the main sequence in order (the labels course-core placed; R-16-01
 * keeps placement and warnings on the client). Without a model (an `IMAGE` course) every value is empty and the stats are zero.
 */
public record CourseModelFacts(Double designLengthM, Double designWidthM, Double canvasWidth, Double canvasHeight, String origin,
        String courseGrade, String courseType, CourseStats stats, List<String> unknownObstacleIds) {
    static final String MAIN_SEQUENCE = "n1";
    static final String UNKNOWN_TYPE = "Unknown";

    public static CourseModelFacts of(JsonNode model) {
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
                new CourseStats(model.path("obstacles").size(), pathLength(model.path("numbers")), new CourseBbox(length, width)), List.copyOf(unknown));
    }

    /** The polyline through the main sequence's numbered labels (`sequenceNumber` ascending), rounded to the centimetre. */
    static double pathLength(JsonNode numbers) {
        var points = new ArrayList<JsonNode>();
        numbers.forEach(number -> {
            if (number.path("sequenceNumber").isIntegralNumber() && MAIN_SEQUENCE.equals(number.path("sequenceKey").asText(MAIN_SEQUENCE))) {
                points.add(number);
            }
        });
        points.sort(Comparator.comparingLong(number -> number.path("sequenceNumber").asLong()));
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += Math.hypot(points.get(i).path("xMeters").asDouble() - points.get(i - 1).path("xMeters").asDouble(),
                    points.get(i).path("yMeters").asDouble() - points.get(i - 1).path("yMeters").asDouble());
        }
        return Math.round(total * 100) / 100.0;
    }

    private static String text(JsonNode node) { return node.isTextual() ? node.textValue() : null; }
}

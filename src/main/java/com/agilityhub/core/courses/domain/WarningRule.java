package com.agilityhub.core.courses.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Values from the committed course-core v1 schema. */
public enum WarningRule {
    @JsonProperty("outside-ring") OUTSIDE_RING,
    @JsonProperty("border-clearance") BORDER_CLEARANCE,
    @JsonProperty("course-no-go") COURSE_NO_GO,
    @JsonProperty("ring-no-go") RING_NO_GO,
    @JsonProperty("door-clearance") DOOR_CLEARANCE,
    @JsonProperty("course-larger-than-ring") COURSE_LARGER_THAN_RING,
    @JsonProperty("obstacle-inventory") OBSTACLE_INVENTORY
}

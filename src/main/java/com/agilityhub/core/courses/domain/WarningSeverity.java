package com.agilityhub.core.courses.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Values from the committed course-core v1 schema. */
public enum WarningSeverity {
    @JsonProperty("info") INFO,
    @JsonProperty("warning") WARNING,
    @JsonProperty("critical") CRITICAL
}

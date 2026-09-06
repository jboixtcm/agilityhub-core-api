package com.agilityhub.core.platform.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record ParameterDefinition(String key, String type, @JsonProperty("default") Object defaultValue,
                                  String block, String labelKey, String helpKey, Map<String, Object> constraints,
                                  List<ClubModule> modules, String scope, String editableBy, boolean restartRequired,
                                  String documentType, String documentDefault) {
    public ParameterDefinition {
        defaultValue = ImmutableValues.freeze(defaultValue);
        constraints = ImmutableValues.map(constraints); modules = List.copyOf(modules);
    }
}

package com.agilityhub.core.clubs.census.domain;

public final class FreeTrainingPolicy {
    private FreeTrainingPolicy() { }
    public record Result(boolean allowed, String source, Boolean override) { }
    public static Result evaluate(Boolean override, boolean levelsEnabled, boolean levelGrants) {
        return new Result(override == null ? levelsEnabled && levelGrants : override, override == null ? "LEVEL" : "MANUAL", override);
    }
}

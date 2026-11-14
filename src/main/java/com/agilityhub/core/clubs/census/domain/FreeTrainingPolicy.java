package com.agilityhub.core.clubs.census.domain;

public final class FreeTrainingPolicy {
    private FreeTrainingPolicy() { }
    public record Result(boolean allowed, String source, Boolean override) { }
    public static boolean hasLicense(java.util.List<java.util.Map<String,Object>> licenses) {
        return licenses != null && licenses.stream().anyMatch(license -> license != null
                && license.get("organisation") instanceof String org && !org.isBlank()
                && license.get("number") instanceof String number && !number.isBlank());
    }
    public static Result evaluate(Boolean override, boolean levelsEnabled, boolean levelGrants, boolean licenseSatisfied) {
        boolean authorized = override == null ? levelsEnabled && levelGrants : override;
        return new Result(licenseSatisfied && authorized, override == null ? "LEVEL" : "MANUAL", override);
    }
}

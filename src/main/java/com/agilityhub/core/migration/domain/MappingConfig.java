package com.agilityhub.core.migration.domain;

import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Versioned adapter: positions disambiguate Playoff's duplicate birth-date headings. */
public record MappingConfig(int version, String defaultClub, int ageWarningYears, int suspectBirthYears, String inferredDogPrefix, Map<String,String> statuses, Map<String,String> plans,
        Set<String> unresolvedPlans, Set<String> withoutPlan, Set<String> familyPlans, Set<String> instructorPlans, Map<String,String> levels, Map<String,String> levelWarnings,
        Map<String,String> levelFlags, Set<String> unresolvedLevels, String photoOwner, Map<String,InputFile> files) {
    public static final int VERSION = 2;
    public static final String DEFAULT = "/migration/playoff-v" + VERSION + ".yaml";
    public record Column(int at, String header, String field, String anonymize) { }
    public record InputFile(String name, boolean required, List<Column> columns) { }
    public static String normalize(String value) { return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    public static MappingConfig load(Path path) {
        try (InputStream input = path == null ? MappingConfig.class.getResourceAsStream(DEFAULT) : Files.newInputStream(path)) {
            var config = new ObjectMapper(new YAMLFactory()).readValue(input, MappingConfig.class);
            config.validate(); return config;
        } catch (IOException | IllegalArgumentException failure) { throw new ApiException(ErrorCode.MAPPING_INVALID); }
    }
    public void validate() {
        if (version != VERSION || defaultClub == null || defaultClub.isBlank() || ageWarningYears < 1 || suspectBirthYears < 1
                || inferredDogPrefix == null || statuses == null || plans == null || levels == null || files == null
                || unresolvedPlans == null || withoutPlan == null || !Collections.disjoint(withoutPlan, plans.keySet()) || !Collections.disjoint(withoutPlan, unresolvedPlans)
                || familyPlans == null || instructorPlans == null || levelFlags == null || unresolvedLevels == null
                || levelWarnings == null || !levels.keySet().containsAll(levelWarnings.keySet()) || !Set.of("LEVEL_PENDING").containsAll(levelWarnings.values())
                || !"DOG".equals(photoOwner)
                || !Set.of("ACTIVE","LEFT","SKIP").containsAll(statuses.values())
                || !files.keySet().equals(Set.of("members", "plans", "levels", "groups", "team", "persons"))) { throw new ApiException(ErrorCode.MAPPING_INVALID); }
        var names = new HashSet<String>();
        for (var file : files.values()) {
            var positions = new HashSet<Integer>(); var fields = new HashSet<String>();
            if (file == null || file.name() == null || file.name().isBlank() || !names.add(file.name())
                    || !Path.of(file.name()).getFileName().toString().equals(file.name()) || file.columns() == null || file.columns().isEmpty()) { throw new ApiException(ErrorCode.MAPPING_INVALID); }
            for (var column : file.columns()) {
                if (column == null || column.at() < 1 || column.at() > file.columns().size() || !positions.add(column.at())
                        || column.field() == null || column.field().isBlank() || !fields.add(column.field()) || column.header() == null || column.header().isBlank()
                        || column.anonymize() == null
                        || !Set.of("keep", "id", "number", "redact", "name", "surname", "document", "passport", "phone", "postal", "address", "email", "iban", "dog", "chip", "license").contains(column.anonymize())) {
                    throw new ApiException(ErrorCode.MAPPING_INVALID);
                }
            }
        }
    }
}

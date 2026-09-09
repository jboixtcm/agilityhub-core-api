package com.agilityhub.core.migration.domain;

import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Versioned adapter: positions disambiguate Playoff's duplicate birth-date headings. */
public record MappingConfig(int version, String defaultClub, int ageWarningYears, int suspectBirthYears, String inferredDogPrefix, Map<String,String> statuses, Map<String,String> plans,
        Set<String> unresolvedPlans, Set<String> familyPlans, Set<String> instructorPlans, Map<String,String> levels,
        Map<String,String> levelFlags, Set<String> unresolvedLevels, Map<String,InputFile> files) {
    public record Column(int at, String header, String field, String anonymize) { }
    public record InputFile(String name, boolean required, List<Column> columns) { }
    public static String normalize(String value) { return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    public static MappingConfig load(Path path) {
        try (InputStream input = path == null ? MappingConfig.class.getResourceAsStream("/migration/playoff-v1.yaml") : Files.newInputStream(path)) {
            var config = new ObjectMapper(new YAMLFactory()).readValue(input, MappingConfig.class);
            config.validate(); return config;
        } catch (IOException | IllegalArgumentException failure) { throw new ApiException(ErrorCode.MAPPING_INVALID); }
    }
    public void validate() {
        if (version != 1 || defaultClub == null || statuses == null || plans == null || levels == null
                || !files.keySet().equals(Set.of("members", "plans", "levels", "groups", "team"))) { throw new ApiException(ErrorCode.MAPPING_INVALID); }
        for (var file : files.values()) {
            var positions = new HashSet<Integer>(); var fields = new HashSet<String>();
            if (!Path.of(file.name()).getFileName().toString().equals(file.name()) || file.columns().isEmpty()) { throw new ApiException(ErrorCode.MAPPING_INVALID); }
            for (var column : file.columns()) {
                if (column.at() < 1 || !positions.add(column.at()) || !fields.add(column.field()) || column.header().isBlank()
                        || !Set.of("keep", "id", "number", "redact", "name", "surname", "document", "passport", "phone", "postal", "address", "email", "iban", "dog", "chip", "license").contains(column.anonymize())) {
                    throw new ApiException(ErrorCode.MAPPING_INVALID);
                }
            }
        }
    }
}

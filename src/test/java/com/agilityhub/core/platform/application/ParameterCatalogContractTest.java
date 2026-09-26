package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.domain.ParameterValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ParameterCatalogContractTest {
    @Test void T_02_03_catalogMatchesDocumentKeysTypesAndActualDefaults() throws Exception {
        var catalog = new ParameterCatalog(new ObjectMapper());
        var document = document();
        assertThat(catalog.entries().keySet()).containsExactlyInAnyOrderElementsOf(document.keySet());
        document.forEach((key, expected) -> {
            var actual = catalog.get(key);
            assertThat(actual.type()).as(key).isEqualTo(expected.type().split(" ")[0]);
            assertThat(actual.documentType()).as(key).isEqualTo(expected.type());
            assertThat(actual.documentDefault()).as(key).isEqualTo(expected.source());
            assertThat(actual.defaultValue()).as(key).isEqualTo(expected.value());
            assertThat(actual.labelKey()).isEqualTo("admin-settings:param." + key + ".label");
            assertThat(actual.helpKey()).isEqualTo("admin-settings:param." + key + ".help");
            assertThat(actual.block()).isNotBlank();
            assertThat(actual.scope()).isIn("club", "ring", "level");
            assertThat(actual.editableBy()).isIn("CLUB", "PLATFORM");
            assertThat(actual.restartRequired()).isFalse();
            if (actual.defaultValue() != null) { new ParameterValidator().validate(actual, actual.defaultValue(), "ca", "EUR"); }
        });
        assertThat(catalog.get("bookings.lateCancelThresholdMinutes").defaultValue()).isEqualTo(240);
        assertThat(catalog.get("files.allowedTypes").defaultValue()).asList().contains("text/plain");
        assertThatThrownBy(() -> catalog.get("invented.key")).isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
        assertThatThrownBy(() -> catalog.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
        System.out.println("catalog.yaml parameter keys: " + catalog.entries().size());
        System.out.println("CATALEG_PARAMETRES.md expanded effective parameter keys: " + document.size());
        System.out.println("Markdown grouped keys/jobs expanded; Club binding rows excluded; Annex amendments folded");
    }
    /**
     * E5-T20 step 2 (E4-W06 question 3): every key sits in the D11 block the document gives it («— bloc X» of its section, or
     * the «Bloc D11» column of Annex A), and a `sistema` key is exactly one the club cannot edit (CATALEG_PARAMETRES 26-09).
     */
    @Test void T_02_03_everyKeyIsInItsDocumentedD11BlockAndOnlySystemKeysArePlatformOnly() throws Exception {
        var catalog = new ParameterCatalog(new ObjectMapper());
        var blocks = documentBlocks();
        assertThat(blocks.keySet()).containsExactlyInAnyOrderElementsOf(catalog.entries().keySet());
        var mismatches = new ArrayList<String>();
        blocks.forEach((key, block) -> {
            var actual = catalog.get(key);
            if (!actual.block().equals(block)) { mismatches.add(key + ": block " + actual.block() + ", document " + block); }
            if (actual.block().equals("system") != actual.editableBy().equals("PLATFORM")) { mismatches.add(key + ": block " + actual.block() + " editableBy " + actual.editableBy()); }
        });
        assertThat(mismatches).isEmpty();
        assertThat(catalog.get("signup.onboardingFields").block()).isEqualTo("signup");
        assertThat(catalog.get("learn.baseUrl").editableBy()).isEqualTo("PLATFORM");
    }
    static final Map<String, String> BLOCKS = Map.ofEntries(Map.entry("sistema", "system"), Map.entry("Alta i consentiments", "signup"),
            Map.entry("Classes", "classes"), Map.entry("Llista d'espera", "waitlist"), Map.entry("Entrenaments", "training"),
            Map.entry("Club i pistes", "club"), Map.entry("Quotes i remesa", "billing"), Map.entry("Comunicacions", "messaging"),
            Map.entry("Recorreguts", "courses"), Map.entry("Privacitat i auditoria", "privacy"), Map.entry("Processos automàtics", "jobs"));
    /** Key → catalog block id, read from the document: the section header, the «sistema» sentence, or Annex A's «Bloc D11» cell. */
    static Map<String, String> documentBlocks() throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        String section = null; boolean annex = false;
        for (String line : Files.readAllLines(Path.of("docs/specs/00-transversal/CATALEG_PARAMETRES.md"))) {
            if (line.startsWith("## ")) {
                annex = line.contains("Annex A");
                section = line.contains("— bloc ") ? line.substring(line.indexOf("— bloc ") + "— bloc ".length()).strip() : null;
            }
            if (line.startsWith("Paràmetres de **sistema**")) { section = "sistema"; }
            if (!line.startsWith("| `")) { continue; }
            String[] cells = Arrays.stream(line.split("\\|", -1)).map(String::strip).toArray(String[]::new);
            if (cells[2].startsWith("(CLUB")) { continue; }
            String documented = annex ? cells[cells.length - 3] : section;
            assertThat(documented).as(line).isNotNull();
            String block = BLOCKS.entrySet().stream().filter(entry -> documented.startsWith(entry.getKey())).map(Map.Entry::getValue)
                    .findFirst().orElseThrow(() -> new AssertionError("Unknown D11 block: " + documented));
            List<String> keys = new ArrayList<>();
            var matcher = Pattern.compile("`([^`]+)`").matcher(cells[1]);
            while (matcher.find()) { keys.add(matcher.group(1)); }
            if (keys.getFirst().equals("files.allowedTypes")) { keys = new ArrayList<>(List.of(keys.getFirst())); }
            if (keys.getFirst().equals("jobs.<nom>.enabled")) {
                String names = cells[1].substring(cells[1].indexOf('(') + 1, cells[1].lastIndexOf(')'));
                keys = Arrays.stream(names.split(" · ")).map(name -> "jobs." + name + ".enabled").toList();
            }
            String prefix = keys.getFirst().substring(0, keys.getFirst().lastIndexOf('.'));
            for (String key : keys) { result.put(key.contains(".") ? key : prefix + "." + key, block); }
        }
        return result;
    }
    static Map<String, Expected> document() throws Exception {
        Map<String, Expected> entries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of("docs/specs/00-transversal/CATALEG_PARAMETRES.md"))) {
            if (!line.startsWith("| `")) { continue; }
            String[] cells = Arrays.stream(line.split("\\|", -1)).map(String::strip).toArray(String[]::new);
            if (cells[2].startsWith("(CLUB")) { continue; }
            List<String> keys = new ArrayList<>();
            var matcher = Pattern.compile("`([^`]+)`").matcher(cells[1]);
            while (matcher.find()) { keys.add(matcher.group(1)); }
            if (keys.getFirst().equals("files.allowedTypes")) { keys = new ArrayList<>(List.of(keys.getFirst())); }
            if (keys.getFirst().equals("jobs.<nom>.enabled")) {
                String names = cells[1].substring(cells[1].indexOf('(') + 1, cells[1].lastIndexOf(')'));
                keys = Arrays.stream(names.split(" · ")).map(name -> "jobs." + name + ".enabled").toList();
            }
            String prefix = keys.getFirst().substring(0, keys.getFirst().lastIndexOf('.'));
            List<String> types = keys.size() > 1 ? Arrays.asList(cells[2].split(" · ")) : List.of(cells[2]);
            List<String> values = Arrays.asList(cells[3].split(" · "));
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i).contains(".") ? keys.get(i) : prefix + "." + keys.get(i);
                String type = types.size() == keys.size() ? types.get(i) : cells[2];
                String source = values.size() == keys.size() ? values.get(i) : cells[3];
                if (key.equals("files.allowedTypes") && source.equals("—")) {
                    var previous = entries.get(key);
                    var amended = new ArrayList<Object>((List<?>) previous.value()); amended.add("text/plain");
                    entries.put(key, new Expected(previous.type(), previous.source() + " + text/plain", amended));
                } else { entries.put(key, new Expected(type, source, defaultValue(key, type, source))); }
            }
        }
        return entries;
    }
    private static Object defaultValue(String key, String type, String source) {
        if (source.startsWith("`{\"") || source.startsWith("`[") || source.startsWith("`\"")) {
            try { return new ObjectMapper().readValue(source.substring(1, source.length() - 1), Object.class); }
            catch (java.io.IOException invalid) { throw new AssertionError("Invalid documented JSON: " + key, invalid); }
        }
        String text = source.replace("`", "").replace("**", "");
        String kind = type.split(" ")[0];
        if (Set.of("int", "duration").contains(kind)) { return Integer.valueOf(text.split(" ")[0]); }
        if (kind.equals("bool")) { return Boolean.valueOf(text); }
        if (kind.equals("decimal") || type.equals("json decimal")) { return Double.valueOf(text); }
        if (kind.equals("enum") || kind.equals("time")) { return text.split(" ")[0]; }
        if (kind.equals("money")) { return Map.of("amountMinor", new java.math.BigDecimal(text.replace(" €", "").replace(',', '.')).movePointRight(2).intValueExact(), "currency", "EUR"); }
        if (kind.equals("string")) { return key.equals("messaging.email.fromName") ? text.split(" / ")[0] : text; }
        if (kind.equals("localizedText")) { return Map.of("ca", text.substring(1, text.length() - 1)); }
        return switch (key) {
            case "bookings.weekOpensAt" -> {
                String[] parts = text.replace("{", "").replace("}", "").split(", ");
                yield Map.of("dayOfWeek", parts[0], "time", parts[1]);
            }
            case "coverage.thresholds" -> {
                String[] parts = text.replace("{", "").replace("}", "").split(", ");
                yield Map.of("ok", Integer.valueOf(parts[0]), "tight", Integer.valueOf(parts[1]), "short", Integer.valueOf(parts[2]));
            }
            case "club.openingHours" -> {
                assertThat(text).startsWith("dl–dg ");
                String[] times = text.substring(6).split("–");
                Map<String, Object> days = new LinkedHashMap<>();
                for (var day : java.time.DayOfWeek.values()) { days.put(day.name(), Map.of("open", times[0], "close", times[1])); }
                yield days;
            }
            case "club.holidays" -> { assertThat(text).isEqualTo("—"); yield List.of(); }
            case "messaging.reminderOptionsMinutes" -> Arrays.stream(text.split(",")).map(Integer::valueOf).toList();
            case "files.allowedTypes" -> Arrays.asList(text.split(", "));
            case "census.bookingBlockReasons" -> Arrays.stream(text.split(" · ")).map(label -> Map.of("ca", label)).toList();
            default -> throw new AssertionError("Untranscribed default: " + key);
        };
    }
    record Expected(String type, String source, Object value) { }
}

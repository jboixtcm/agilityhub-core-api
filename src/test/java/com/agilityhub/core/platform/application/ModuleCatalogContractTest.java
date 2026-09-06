package com.agilityhub.core.platform.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleCatalogContractTest {
    private static final Pattern KEY = Pattern.compile("`([A-Z][A-Z_]*)`");

    @Test
    void T_02_09_moduleCatalogMatchesDocument() throws Exception {
        var lines = Files.readAllLines(Path.of("docs/specs/00-transversal/CATALEG_MODULS.md"));
        Map<String, String[]> rows = new LinkedHashMap<>();
        lines.stream().filter(line -> line.startsWith("| `")).forEach(line -> {
            String[] cells = Arrays.stream(line.split("\\|")).map(String::trim).toArray(String[]::new);
            assertThat(rows.put(keys(cells[1]).getFirst(), cells)).isNull();
        });
        assertThat(rows).hasSize(16);
        assertThat(Arrays.stream(Module.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(rows.keySet());

        String selfServiceRule = lines.stream().filter(line -> line.startsWith("1. ")).findFirst().orElseThrow();
        var selfService = keys(selfServiceRule);
        String presetRule = lines.stream().filter(line -> line.startsWith("5. ")).findFirst().orElseThrow();
        var minim = keys(presetRule.substring(presetRule.indexOf("només")));
        for (Module module : Module.values()) {
            String[] row = rows.get(module.name());
            // An em dash means no prerequisite; parenthesized billing is only for the fee.
            var dependencies = row[2].startsWith("—") ? List.<String>of() : keys(row[2]);
            assertThat(module.dependsOn().stream().map(Enum::name).toList())
                    .as("%s dependencies", module).containsExactlyElementsOf(dependencies);
            assertThat(module.selfService()).as("%s self-service", module).isEqualTo(selfService.contains(module.name()));
            assertThat(row[6]).isIn("on", "off");
            assertThat(module.defaultEnabled(Module.Preset.CANIC)).as("%s CANIC default", module).isEqualTo(row[6].equals("on"));
            assertThat(module.defaultEnabled(Module.Preset.MINIM)).as("%s MINIM default", module).isEqualTo(minim.contains(module.name()));
            System.out.printf("%s dependsOn=%s selfService=%s MINIM=%s CANIC=%s%n", module, module.dependsOn(),
                    module.selfService(), module.defaultEnabled(Module.Preset.MINIM), module.defaultEnabled(Module.Preset.CANIC));
        }
        for (Module.Preset preset : Module.Preset.values()) {
            new ModuleDependencyValidator().validate(Module.defaults(preset));
            assertThat(Module.defaults(preset)).containsExactlyInAnyOrderElementsOf(
                    Arrays.stream(Module.values()).filter(module -> module.defaultEnabled(preset)).toList());
            assertThatThrownBy(() -> Module.defaults(preset).clear()).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThatThrownBy(() -> Module.PACKS.dependsOn().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static List<String> keys(String text) {
        return KEY.matcher(text).results().map(match -> match.group(1)).toList();
    }
}

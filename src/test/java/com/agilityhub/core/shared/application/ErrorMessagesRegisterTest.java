package com.agilityhub.core.shared.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T18 step 7 (ruling E153, `docs/DECISIONS_COPY_A39-A41.md` §A40.4; INC-41 #21): the api's error messages speak to the reader
 * in tu/tú, with the wording the web adopts for the same 26 codes in E11-W05 (the table's «proposta» column; `en` does not change).
 * The api keeps its placeholders: `INTERNAL_ERROR` still prints `{traceId}`.
 */
class ErrorMessagesRegisterTest {
    static final Path DECISIONS = Path.of("docs/DECISIONS_COPY_A39-A41.md");
    /** A40.4's row: `| n | `CODE` | ca now → **ca proposal** | es now → **es proposal** |`. */
    static final Pattern ROW = Pattern.compile("^\\| \\d+ \\| `([A-Z_]+)` \\| .*? → \\*\\*(.+?)\\*\\* \\| .*? → \\*\\*(.+?)\\*\\* \\|$");
    /** A40.4's markers of «vosaltres» (ca) and «usted» (es), the ones of E11-W05 step 4 and the ones the table adds. */
    static final Map<String, Pattern> PLURAL_OR_FORMAL = Map.of(
            "ca", Pattern.compile("\\b(vostre|vostra|vostres|podeu|Torneu|Seleccioneu|Trieu|indiqueu|Introduïu|teniu|Inicieu|Completeu|Accepteu|Activeu"
                    + "|Confirmeu|Reviseu|Actualitzeu)\\b", Pattern.UNICODE_CHARACTER_CLASS),
            "es", Pattern.compile("\\b(Vuelva|Seleccione|Elija|indique|su cuenta|Introduzca|Complete|Acepte|Active|Confirme|Revise|Inicie|Actualícelo"
                    + "|No puede|No tiene|su propio)\\b", Pattern.UNICODE_CHARACTER_CLASS));

    static Properties messages(String locale) throws Exception {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/messages/messages_" + locale + ".properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
    /** The 26 rows of A40.4: code → {ca, es} proposal. */
    static Map<String, Map<String, String>> proposals() throws Exception {
        var lines = Files.readAllLines(DECISIONS);
        int start = lines.indexOf(lines.stream().filter(line -> line.startsWith("### A40.4")).findFirst().orElseThrow());
        var rows = new LinkedHashMap<String, Map<String, String>>();
        for (String line : lines.subList(start, lines.size())) {
            if (line.startsWith("### A40.5")) { break; }
            var row = ROW.matcher(line);
            if (row.matches()) { rows.put(row.group(1), Map.of("ca", row.group(2), "es", row.group(3))); }
        }
        return rows;
    }

    @Test void E11_T18_A40_4_theErrorMessagesOfTheTableUseTheWordingTheWebAdopts() throws Exception {
        var proposals = proposals();
        assertThat(proposals).hasSize(26).containsKeys("ACCOUNT_BLOCKED", "INTERNAL_ERROR", "STALE_VERSION", "TASK_NOT_DONE");
        for (String locale : List.of("ca", "es")) {
            var messages = messages(locale);
            proposals.forEach((code, texts) -> {
                String message = messages.getProperty("error." + code);
                assertThat(message).as(locale + " " + code).isNotNull();
                // The placeholder stays (MessageParityTest): the reference code the web leaves out is the response's traceId.
                if (code.equals("INTERNAL_ERROR")) { assertThat(message).as(locale + " " + code).contains(" {traceId} "); }
                assertThat(message.replace(" {traceId}", "")).as(locale + " " + code).isEqualTo(texts.get(locale));
            });
        }
        System.out.println("E11-T18 A40.4: " + proposals.size() + " codes × ca/es match the approved tu/tú wording");
    }

    @Test void E11_T18_A40_4_noErrorMessageSpeaksInVosaltresOrUsted() throws Exception {
        for (String locale : List.of("ca", "es")) {
            var messages = messages(locale);
            var found = messages.stringPropertyNames().stream().filter(key -> key.startsWith("error."))
                    .filter(key -> PLURAL_OR_FORMAL.get(locale).matcher(messages.getProperty(key)).find()).sorted().toList();
            assertThat(found).as(locale).isEmpty();
            System.out.println("E11-T18 A40.4 " + locale + ": no error.* message in «vosaltres»/«usted» (" + messages.stringPropertyNames().stream()
                    .filter(key -> key.startsWith("error.")).count() + " checked)");
        }
    }
}

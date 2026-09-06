package com.agilityhub.core.shared.domain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ErrorCatalogContractTest {
    @Test void E0_T04_errorCatalogMatchesEveryConcreteCodeAndCanonicalStatus() throws Exception {
        var codes = new LinkedHashSet<String>();
        var pattern = Pattern.compile("`([A-Z][A-Z_]+)`");
        for (String row : Files.readAllLines(Path.of("docs/specs/00-transversal/CATALEG_ERRORS.md"))) {
            if (!row.startsWith("|") && !row.startsWith("Transversals:")) { continue; }
            var matcher = pattern.matcher(row);
            var status = Pattern.compile("^\\| (\\d{3}) \\|").matcher(row);
            Integer expectedStatus = status.find() ? Integer.valueOf(status.group(1)) : null;
            while (matcher.find()) {
                codes.add(matcher.group(1));
                if (expectedStatus != null) {
                    assertThat(ErrorCode.valueOf(matcher.group(1)).httpStatus()).isEqualTo(expectedStatus);
                }
            }
        }
        assertThat(Arrays.stream(ErrorCode.values()).map(Enum::name)).containsExactlyInAnyOrderElementsOf(codes);
        assertThat(ErrorCode.IDEMPOTENCY_KEY_REUSED.httpStatus()).isEqualTo(409);
        for (ErrorCode code : ErrorCode.values()) {
            var exception = new ApiException(code);
            assertThat(exception.code()).isEqualTo(code);
            assertThat(exception.getMessage()).isEqualTo(code.name());
            assertThat(exception.details()).isEmpty();
            assertThat(code.httpStatus()).isBetween(400, 501);
        }
    }
}

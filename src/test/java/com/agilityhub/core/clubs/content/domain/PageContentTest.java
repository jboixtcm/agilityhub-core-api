package com.agilityhub.core.clubs.content.domain;

import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PageContentTest {
    void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(error.details()).containsKey("fieldErrors");
        });
    }
    @ParameterizedTest
    @ValueSource(strings = {"<script>alert(1)</script>", "<img src=x onerror=alert(1)>", "ok <b>bold</b>", "![image](https://example.test)",
            "![image][ref]\n\n[ref]: /image", "```\ncode\n```", "`code`", "    code", "> quote", "---",
            "[click](javascript:alert%281%29)", "[click](JaVaScRiPt:alert)", "[click](java&#x73;cript:alert)",
            "[click][ref]\n\n[ref]: data:text/html,payload", "[click](//example.test)", "[click](file:/tmp/secret)",
            "[click](https://example.test/\\\\evil)", "[click](<https://example.test/a b>)", "[unused]: vbscript:payload"})
    void T_05_CP_04_rejectsDisallowedMarkdownAndUnsafeDecodedLinks(String text) { invalid(() -> LimitedMarkdown.validate(text, "body.ca")); }
    @ParameterizedTest
    @ValueSource(strings = {"# Heading\n\nA **bold** and *italic* paragraph.\n\n- First\n- Second\n\n1. One\n2. Two",
            "[relative](/rules) [anchor](#section) [mail](mailto:club@example.test) [web](HTTPS://example.test)",
            "[reference][safe]\n\n[safe]: https://example.test", "line  \nbreak\nsoft", "&lt;script&gt; literal", "[Text pending]", ""})
    void T_05_CP_04_acceptsOnlySupportedSyntax(String text) { LimitedMarkdown.validate(text, "body.ca"); }
    @Test void T_05_CP_04_validatesKeysDefaultTranslationAndPerLocaleSize() {
        for (String key : List.of("RULES", "PRIVACY", "IMAGE_CONSENT", "WELCOME_GUIDE", "aa", "a".repeat(40))) {
            var page = PageContent.validate(key, Map.of("ca", "Title"), Map.of("ca", "a".repeat(20000), "en", "Draft"), true, "ca", List.of("ca", "es"));
            assertThat(page.key()).isEqualTo(key); assertThat(page.body().values().get("en")).isEqualTo("Draft");
        }
        for (String key : Arrays.asList(null, "a", "Mixed", "a".repeat(41), "has_space", "with/slash")) {
            invalid(() -> PageContent.validate(key, Map.of("ca", "Title"), Map.of("ca", "Body"), true, "ca", List.of("ca")));
        }
        var nullValue = new HashMap<String, String>(); nullValue.put("ca", null);
        for (Map<String, String> body : Arrays.<Map<String, String>>asList(null, Map.of(), Map.of("es", "Missing default"), nullValue,
                Map.of("ca", " "), Map.of("ca", "a".repeat(20001)), Map.of("ca", "ok", "xx", "bad"))) {
            invalid(() -> PageContent.validate("RULES", Map.of("ca", "Title"), body, false, "ca", List.of("ca")));
        }
        invalid(() -> PageContent.validate("RULES", Map.of("ca", "t".repeat(201)), Map.of("ca", "Body"), true, "ca", List.of("ca")));
        invalid(() -> LimitedMarkdown.validate("[a](<java\u0000script:bad>)", "body"));
    }
}

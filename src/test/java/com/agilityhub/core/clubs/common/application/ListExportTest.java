package com.agilityhub.core.clubs.common.application;

import java.util.*;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.application.lists.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;

class ListExportTest {
    static { System.setProperty("java.awt.headless", "true"); }
    static void authentication(String role, boolean impersonated) {
        var jwt = Jwt.withTokenValue("fictional").header("alg", "none").subject("example").claim("imp", impersonated).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(() -> "ROLE_" + role)));
    }
    @Test void T_03_11_applicationPolicyRejectsNonAdminAndMasksNestedBankAccounts() {
        var policy = new ExportPolicy();
        try {
            SecurityContextHolder.clearContext(); assertThat(ListAccess.admin()).isFalse();
            assertThatThrownBy(() -> policy.requireAllowed("xlsx")).isInstanceOf(ApiException.class);
            authentication("MEMBER", false); assertThatThrownBy(ListAccess::account).isInstanceOf(ApiException.class);
            authentication("ADMIN", true); assertThatThrownBy(ListAccess::account).isInstanceOf(ApiException.class);
            authentication("INSTRUCTOR", false); assertThatThrownBy(() -> policy.requireAllowed("xlsx")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
            authentication("ADMIN", false); policy.requireAllowed("xlsx"); policy.requireAllowed("pdf");
            assertThatThrownBy(() -> policy.requireAllowed("csv")).isInstanceOf(ApiException.class);
            assertThat(policy.mask(Map.of("rows", List.of(Map.of("iban", "ES000000002231", "name", "Example")))) .toString()).contains("2231", "Example").doesNotContain("ES000000");
            assertThat(policy.mask(Map.of("iban", ""))).isEqualTo(Map.of("maskedAccount", ""));
            assertThat(policy.mask(null)).isNull();
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void T_03_11_fileContentsPreserveTextCellsUnicodeAndLongPdfValues() throws Exception {
        var renderer = new ListExportRenderer();
        String formula = "=HYPERLINK(\"https://example.test\",\"Example\")";
        var row = new LinkedHashMap<String, Object>(); row.put("name", formula); row.put("payment", Map.of("maskedAccount", "···· 2231")); row.put("empty", null);
        var rows = List.<Map<String, Object>>of(row);
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(renderer.render("xlsx", "Example", "#112233", "members", List.of("name", "payment", "empty"), rows)))) {
            var cell = book.getSheetAt(0).getRow(1).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING); assertThat(cell.getStringCellValue()).isEqualTo(formula);
        }
        var longRows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 25; i++) { longRows.add(Map.of("name", "Fictional Àlex · Pérez " + i, "details", "wrapping text ".repeat(35) + "END-OF-VALUE", "dogs", List.of("Example Dog", "Example Puppy"))); }
        byte[] pdf = renderer.render("pdf", "Example Agility Club", "#235544", "members", List.of("name", "details", "dogs"), longRows);
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isGreaterThan(1); assertThat(text).contains("Àlex", "Pérez", "END-OF-VALUE", "24");
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/list-export-qa"));
            java.nio.file.Files.write(java.nio.file.Path.of("target/list-export-qa/members.pdf"), pdf);
            javax.imageio.ImageIO.write(new org.apache.pdfbox.rendering.PDFRenderer(document).renderImageWithDPI(0, 90), "png", java.nio.file.Path.of("target/list-export-qa/page-1.png").toFile());
        }
        assertThat(renderer.render("pdf", "Example", "#112233", "members", List.of("name"), List.of())).isNotEmpty();
        assertThat(renderer.render("pdf", "Example", "#112233", "members", List.of("name"), List.of(Map.of("name", "Line\nEmoji 🐕")))).isNotEmpty();
    }
}

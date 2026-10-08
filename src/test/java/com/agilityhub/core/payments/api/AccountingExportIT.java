package com.agilityhub.core.payments.api;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.support.AuditCovers;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AccountingExportIT extends BillingItSupport {
    @org.springframework.beans.factory.annotation.Autowired com.agilityhub.core.clubs.common.application.ExportJobCoordinator coordinator;
    @org.springframework.beans.factory.annotation.Autowired com.agilityhub.core.clubs.common.application.ExportJobRenderer renderer;
    @BeforeEach void clearExports() {
        for (String collection : List.of("export_jobs", "export_work", "export_locks")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
    }
    @Test @AuditCovers(AuditAction.DATA_EXPORTED)
    void T_12_19_csvContainsEveryInvoiceLineAndLocalizedAmountsWithBom() throws Exception {
        var remittance = run("2026-09", simulate("2026-09").path("id").asText()).path("remittance");
        var response = call(admin(get("/api/v1/billing/exports").param("period", "2026-09").header("Accept-Language", "ca")));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        byte[] bytes = response.getContentAsByteArray(); assertThat(Arrays.copyOf(bytes, 3)).containsExactly((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
        String csv = new String(bytes, StandardCharsets.UTF_8); var lines = csv.lines().toList();
        int expected = invoices().stream().mapToInt(i -> i.getList("lines", Document.class).size()).sum();
        assertThat(lines).hasSize(expected + 1); assertThat(lines.getFirst()).contains("Concepte", "Número d’abonat");
        assertThat(csv).contains("60,00", "90,00", "2026-0912", ";\"Remesat\";", ";\"Pendent\";").doesNotContain("€", IBAN, "COLLECTING");
        // The remittance column is the pain.008 MsgId the bank knows, never the internal id.
        assertThat(csv).contains(";\"" + remittance.path("messageId").asText() + "\";").doesNotContain(remittance.path("id").asText());
        var entries = com.agilityhub.core.migration.application.PlayoffTable.csv(csv);
        long total = entries.subList(1, entries.size()).stream().mapToLong(r -> new java.math.BigDecimal(r.get(10).replace(',', '.')).movePointRight(2).longValueExact()).sum();
        assertThat(total).isEqualTo(invoices().stream().mapToLong(i -> ((Number) i.get("total", Document.class).get("amountMinor")).longValue()).sum());
        assertThat(events("DataExported")).hasSize(1);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("DATA_EXPORTED")), "audit_entries")).isEqualTo(1);
        var other = call(as(get("/api/v1/billing/exports").param("period", "2026-09").param("format", "csv"), OTHER, "ADMIN", null));
        assertThat(other.getStatus()).isEqualTo(200); assertThat(other.getContentAsString().lines().count()).isEqualTo(1);
    }
    @Test void T_12_19_exportChecksRoleModuleAndFormat() throws Exception {
        for (String role : List.of("MEMBER", "INSTRUCTOR")) {
            error(as(get("/api/v1/billing/exports").param("period", "2026-09"), CLUB, role, "puig"), 403, "FORBIDDEN");
        }
        error(admin(get("/api/v1/billing/exports").param("period", "bad")), 400, "VALIDATION_ERROR");
        error(admin(get("/api/v1/billing/exports").param("period", "2026-09").param("format", "pdf")), 400, "VALIDATION_ERROR");
        modules(CLUB, List.of());
        error(admin(get("/api/v1/billing/exports").param("period", "2026-09")), 404, "MODULE_DISABLED");
    }
    @Test void T_12_19_largeAccountingCsvQueuesClaimsAndDownloadsWithBom() throws Exception {
        run("2026-09", simulate("2026-09").path("id").asText());
        var invoice = invoices().getFirst();
        var lines = new ArrayList<Document>();
        for (int i = 1; i <= 5001; i++) {
            lines.add(new Document(invoice.getList("lines", Document.class).getFirst()).append("lineNo", i));
        }
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB).and("_id").ne(invoice.getString("_id"))), "invoices");
        var largeInvoice = new Update().set("lines", lines);
        for (String amount : List.of("base", "tax", "total")) {
            var money = new Document(lines.getFirst().get(amount, Document.class));
            money.put("amountMinor", ((Number) money.get("amountMinor")).longValue() * lines.size());
            largeInvoice.set(amount, money);
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(invoice.getString("_id"))), largeInvoice, "invoices");
        String id = ok(admin(get("/api/v1/billing/exports").param("period", "2026-09").param("format", "csv")
                .header("Accept-Language", "ca")), 202).path("jobId").asText();
        assertThat(ok(admin(get("/api/v1/exports/" + id)), 200).path("format").asText()).isEqualTo("CSV");
        assertThat(mongo.findById(id, Document.class, "export_jobs")).containsEntry("kind", "ACCOUNTING").containsEntry("status", "QUEUED");
        com.agilityhub.core.clubs.common.persistence.ExportJob claim;
        try (var tenant = com.agilityhub.core.shared.application.TenantContext.open(CLUB)) { claim = coordinator.claim(); }
        assertThat(claim).isNotNull(); assertThat(claim.id()).isEqualTo(id);
        renderer.render(claim, false);
        var ready = ok(admin(get("/api/v1/exports/" + id)), 200);
        assertThat(ready.path("status").asText()).isEqualTo("READY"); assertThat(ready.path("rows").asInt()).isEqualTo(5001);
        var download = call(admin(get(ready.path("downloadUrl").asText())));
        assertThat(download.getStatus()).isEqualTo(200);
        assertThat(Arrays.copyOf(download.getContentAsByteArray(), 3)).containsExactly((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
        assertThat(download.getContentAsString(StandardCharsets.UTF_8).lines().count()).isEqualTo(5002);
        error(as(get("/api/v1/exports/" + id), OTHER, "ADMIN", null), 404, "NOT_FOUND");
        assertThat(events("DataExported")).hasSize(1);
    }
    @Test void T_12_19_xlsxAndCsvKeepThePaidCardReferenceAndCollectionDate() throws Exception {
        club(CLUB, HOST, "Europe/Madrid", List.of(Module.values()), providers(true, true, true));
        run("2026-09", simulate("2026-09").path("id").asText());
        var invoice = invoices().stream().filter(i -> "CARD".equals(i.get("paymentMethod", Document.class).getString("type"))).findFirst().orElseThrow();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(invoice.getString("_id"))),
                new Update().set("status", "PAID").set("paidAt", Date.from(java.time.Instant.parse("2026-09-02T10:00:00Z"))), "invoices");
        assertThat(mongo.updateMulti(Query.query(Criteria.where("clubId").is(CLUB).and("invoiceId").is(invoice.getString("_id"))),
                new Update().set("providerRef", "pi_accounting_fictional").set("status", "SUCCEEDED"), "collections").getMatchedCount()).isEqualTo(1);
        var csv = call(admin(get("/api/v1/billing/exports").param("period", "2026-09").param("format", "csv").header("Accept-Language", "ca")));
        assertThat(csv.getStatus()).isEqualTo(200);
        var rows = com.agilityhub.core.migration.application.PlayoffTable.csv(csv.getContentAsString(StandardCharsets.UTF_8));
        assertThat(rows).filteredOn(r -> r.getFirst().equals(invoice.getString("displayNumber"))).isNotEmpty()
                .allSatisfy(r -> { assertThat(r.get(13)).isEqualTo("02/09/2026"); assertThat(r.get(15)).isEqualTo("pi_accounting_fictional"); });
        var xlsx = call(admin(get("/api/v1/billing/exports").param("period", "2026-09").param("format", "xlsx").header("Accept-Language", "ca")));
        assertThat(xlsx.getStatus()).isEqualTo(200);
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx.getContentAsByteArray()))) {
            var sheet = book.getSheetAt(0); assertThat(sheet.getLastRowNum()).isEqualTo(rows.size() - 1);
            for (int i = 1; i < rows.size(); i++) {
                for (int col : List.of(0, 6, 10, 13, 15)) { assertThat(sheet.getRow(i).getCell(col).getStringCellValue()).isEqualTo(rows.get(i).get(col)); }
            }
        }
    }
}

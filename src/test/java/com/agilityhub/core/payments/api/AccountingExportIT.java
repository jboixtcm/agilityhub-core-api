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
    @BeforeEach void clearExports() {
        for (String collection : List.of("export_jobs", "export_work", "export_locks")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
    }
    @Test @AuditCovers(AuditAction.DATA_EXPORTED)
    void T_12_19_csvContainsEveryInvoiceLineAndLocalizedAmountsWithBom() throws Exception {
        run("2026-09", simulate("2026-09").path("id").asText());
        var response = call(admin(get("/api/v1/billing/exports").param("period", "2026-09").header("Accept-Language", "ca")));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        byte[] bytes = response.getContentAsByteArray(); assertThat(Arrays.copyOf(bytes, 3)).containsExactly((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
        String csv = new String(bytes, StandardCharsets.UTF_8); var lines = csv.lines().toList();
        int expected = invoices().stream().mapToInt(i -> i.getList("lines", Document.class).size()).sum();
        assertThat(lines).hasSize(expected + 1); assertThat(lines.getFirst()).contains("Concepte", "Número d’abonat");
        assertThat(csv).contains("60,00", "90,00", "2026-0912").doesNotContain("€", IBAN);
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
}

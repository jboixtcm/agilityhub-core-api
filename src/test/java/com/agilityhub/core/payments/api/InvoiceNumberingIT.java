package com.agilityhub.core.payments.api;

import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * S12 R-12-08 through the real counter (E8-T02 round 2, review #1 and #2, ruling E87): T-12-04 over `POST /invoices` and
 * `POST /billing/runs` → `InvoiceNumbers` → `InvoiceCounters` → `ClubRepository.reserveInvoiceNumbers` on Mongo. The
 * imported `billing.nextNumber` (912) opens the first series; a new year with `billing.invoiceResetYearly` starts at
 * `2027-0001`, without it the numbers go on; toggling the key from D11 in either direction never reuses a number, also
 * after a rollback gave a block back (the other key's counter is a source of the same series).
 */
class InvoiceNumberingIT extends BillingItSupport {
    static final Instant JANUARY = Instant.parse("2027-01-04T09:00:00Z");

    String manual(String memberId) throws Exception {
        return ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", memberId, "lines", List.of(Map.of("description", "Ajust", "base",
                Map.of("amountMinor", 500, "currency", "EUR"), "taxPercent", 0)), "note", "Numeració"))), 201).path("displayNumber").asText();
    }
    Document counters() { return mongo.findById(CLUB, Document.class, "clubs").get("billing", Document.class).get("counters", Document.class); }
    /** R-12-08 as the partial index sees it: no two receipts that are not cancelled share a series and a number. */
    void numbersAreUnique() {
        var live = invoices().stream().filter(invoice -> !"CANCELLED".equals(invoice.getString("status"))).map(invoice -> invoice.getString("displayNumber")).toList();
        assertThat(live).doesNotHaveDuplicates();
    }

    @Test void T_12_04_theImportedNumberOpensTheSeriesAndANewYearWithTheResetStartsAt0001() throws Exception {
        assertThat(List.of(manual("puig"), manual("vila"))).containsExactly("2026-0912", "2026-0913");
        clock.setInstant(JANUARY);
        assertThat(List.of(manual("puig"), manual("vila"))).containsExactly("2027-0001", "2027-0002");
        assertThat(counters()).containsEntry("2026", 914L).containsEntry("2027", 3L);
    }

    @Test void T_12_04_withoutTheResetTheNumbersGoOnIntoTheNewYear() throws Exception {
        parameter(CLUB, "billing.invoiceResetYearly", false);
        assertThat(List.of(manual("puig"), manual("vila"))).containsExactly("2026-0912", "2026-0913");
        clock.setInstant(JANUARY);
        assertThat(manual("puig")).isEqualTo("2027-0914");
        assertThat(counters()).containsOnlyKeys("{YYYY}").containsEntry("{YYYY}", 915L);
    }

    @Test void T_12_04_R_12_08_togglingTheYearlyResetFromD11NeverReusesANumber() throws Exception {
        assertThat(List.of(manual("puig"), manual("vila"))).containsExactly("2026-0912", "2026-0913");
        // Reset off: the key becomes "{YYYY}", a counter of its own that starts after the series' last receipt (not at 1).
        parameter(CLUB, "billing.invoiceResetYearly", false);
        assertThat(List.of(manual("puig"), manual("vila"))).containsExactly("2026-0914", "2026-0915");
        // Reset on again: the "2026" counter (still at 914) never hands out 0914 or 0915 again.
        parameter(CLUB, "billing.invoiceResetYearly", true);
        assertThat(manual("puig")).isEqualTo("2026-0916");
        assertThat(counters()).containsEntry("2026", 917L).containsEntry("{YYYY}", 916L);
        // The run numbers its block after them, and the partial unique index never meets a duplicate.
        var run = run("2026-09", simulate("2026-09").path("id").asText());
        assertThat(invoices().stream().filter(invoice -> run.at("/run/id").asText().equals(invoice.getString("runId"))).map(invoice -> invoice.getString("displayNumber")))
                .containsExactly("2026-0917", "2026-0918", "2026-0919", "2026-0920", "2026-0921", "2026-0922", "2026-0923", "2026-0924");
        numbersAreUnique();
        // A receipt numbered by the other key after the run blocks its rollback (R-12-14): the block is no longer the last one.
        parameter(CLUB, "billing.invoiceResetYearly", false);
        assertThat(manual("puig")).isEqualTo("2026-0925");
        var refused = call(admin(keyed(post("/api/v1/billing/runs/" + run.at("/run/id").asText() + "/rollback"), Map.of("reason", "Error", "confirmation", "RETROCEDIR"))));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(409);
        assertThat(BillingCycleIT.strings(json(refused).at("/details/reasons"))).containsExactly("MANUAL_INVOICE_AFTER");
        numbersAreUnique();
    }

    @Test void T_12_04_R_12_14_afterARollbackTheOtherKeyStartsFromTheCounterOfTheSameSeries() throws Exception {
        // The imported 912 opens "2026"; the run takes 0912…0919 and its rollback gives them back: the counter is 912 again.
        String runId = run("2026-09", simulate("2026-09").path("id").asText()).at("/run/id").asText();
        ok(admin(keyed(post("/api/v1/billing/runs/" + runId + "/rollback"), Map.of("reason", "Preu equivocat", "confirmation", "RETROCEDIR"))), 200);
        assertThat(counters()).containsEntry("2026", 912L);
        // Reset off: no receipt of 2026 is live, but "2026" says where the series is: 0912, never 0001 (Playoff's 1…911).
        parameter(CLUB, "billing.invoiceResetYearly", false);
        assertThat(manual("puig")).isEqualTo("2026-0912");
        numbersAreUnique();
    }

    @Test void T_12_04_R_12_14_aPatternCounterIsTheSameSeriesOnlyWhenTheSeriesHasReceipts() throws Exception {
        // Without the reset from the start, the run numbers with "{YYYY}" and its rollback gives the block back.
        parameter(CLUB, "billing.invoiceResetYearly", false);
        String runId = run("2026-09", simulate("2026-09").path("id").asText()).at("/run/id").asText();
        ok(admin(keyed(post("/api/v1/billing/runs/" + runId + "/rollback"), Map.of("reason", "Preu equivocat", "confirmation", "RETROCEDIR"))), 200);
        assertThat(counters()).containsOnlyKeys("{YYYY}").containsEntry("{YYYY}", 912L);
        // Reset on: "2026" is new; "{YYYY}" numbered 2026's receipts (rolled back, still there), so it goes on from it.
        parameter(CLUB, "billing.invoiceResetYearly", true);
        assertThat(manual("puig")).isEqualTo("2026-0912");
        // In January the "{YYYY}" counter says nothing about 2027, which has no receipt yet: 2027-0001.
        clock.setInstant(JANUARY);
        assertThat(manual("vila")).isEqualTo("2027-0001");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("series").is("2027")), "invoices")).isEqualTo(1);
        numbersAreUnique();
    }
}

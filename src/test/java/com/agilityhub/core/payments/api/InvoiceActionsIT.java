package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.persistence.BillingDocuments.CollectionRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * S12 §5 invoice actions and reads over September's run of the fictional census (E8-T02): T-12-14 (cash payments in bulk,
 * a SEPA bank return before and after payment, N-10), T-12-18 (a manual adjustment invoice numbered in sequence, the
 * cancellation rules), T-12-12 (immutability), T-12-20 (the member's receipts with the family holder's, the PDF), T-12-21
 * (tenant and roles with real data) and T-12-32 (one payment per Idempotency-Key).
 */
class InvoiceActionsIT extends BillingItSupport {
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired CollectionRepository collectionRepository;
    Map<String, Document> byMember;

    @BeforeEach void september() throws Exception {
        run("2026-09", simulate("2026-09").path("id").asText());
        byMember = new LinkedHashMap<>();
        for (var invoice : invoices()) { byMember.put(invoice.getString("memberId"), invoice); }
    }
    String id(String member) { return byMember.get(member).getString("_id"); }
    Document stored(String id) { return mongo.findById(id, Document.class, "invoices"); }
    long version(String id) { return ((Number) stored(id).get("version")).longValue(); }
    List<Document> collections(String invoiceId) {
        return mongo.find(Query.query(Criteria.where("invoiceId").is(invoiceId)).with(org.springframework.data.domain.Sort.by("createdAt")), Document.class, "collections");
    }

    @Test @AuditCovers(AuditAction.INVOICE_MARKED_PAID)
    void T_12_14_fourCashInvoicesPaidInBulkAllOrNone() throws Exception {
        var cash = List.of(id("vidal"), id("vila"), id("vives"), id("xirau"));
        // One of them COLLECTING (SEPA): the whole selection is refused and nothing moves.
        var mixed = new ArrayList<>(cash); mixed.add(id("puig"));
        var refused = call(admin(keyed(post("/api/v1/invoices/payments"), Map.of("invoiceIds", mixed, "paidAt", "2026-08-25", "channel", "CASH"))));
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(json(refused).path("code").asText()).isEqualTo("INVALID_STATE");
        assertThat(cash).allSatisfy(id -> assertThat(stored(id).getString("status")).isEqualTo("PENDING"));
        var paid = ok(admin(keyed(post("/api/v1/invoices/payments"), Map.of("invoiceIds", cash, "paidAt", "2026-08-24", "channel", "CASH"))), 200);
        assertThat(paid.path("paid").asInt()).isEqualTo(4);
        assertThat(paid.path("invoices")).hasSize(4).allSatisfy(invoice -> assertThat(invoice.path("status").asText()).isEqualTo("PAID"));
        for (String id : cash) {
            assertThat(stored(id).getString("status")).isEqualTo("PAID");
            assertThat(stored(id).getDate("paidAt").toInstant()).isEqualTo(java.time.Instant.parse("2026-08-23T22:00:00Z")); // 24-08 00:00 in Madrid
            // The run's MANUAL attempt is resolved by a SUCCEEDED one, same attempt number, appended.
            assertThat(collections(id)).extracting(c -> c.getString("provider") + " " + c.getString("status") + " " + c.getInteger("attempt"))
                    .containsExactly("MANUAL CREATED 1", "MANUAL SUCCEEDED 1");
            assertThat(collections(id).getLast().getString("channel")).isEqualTo("CASH");
        }
        assertThat(events("InvoicePaid")).hasSize(4).allSatisfy(event -> assertThat(event.get("payload", Document.class).getString("provider")).isEqualTo("MANUAL"));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("INVOICE_MARKED_PAID")), "audit_entries")).isEqualTo(4);
        // S12 §8, §13-10: a receipt paid by hand notifies nobody.
        outbox.dispatch();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-30")), "notifications")).isZero();
        // A paid invoice cannot be paid again.
        error(admin(keyed(post("/api/v1/invoices/" + cash.getFirst() + "/payment"), Map.of("paidAt", "2026-08-25", "channel", "CASH", "version", version(cash.getFirst())))),
                409, "INVALID_STATE");
    }

    @Test @AuditCovers(AuditAction.INVOICE_MARKED_FAILED)
    void T_12_14_T_12_30_aBankReturnFailsACollectingInvoiceAndAPaidOneWithItsSepaCollectionAndNotifiesTheAdmins() throws Exception {
        admin("bill-admin-1", CLUB);
        String eva = id("puig");
        error(admin(keyed(post("/api/v1/invoices/" + eva + "/failure"), Map.of("reason", "Devolució del banc", "at", "2026-09-04", "version", 0))), 400, "VALIDATION_ERROR");
        clock.setInstant(java.time.Instant.parse("2026-09-04T09:00:00Z"));
        error(admin(keyed(post("/api/v1/invoices/" + eva + "/failure"), Map.of("reason", "Devolució del banc", "at", "2026-09-04", "version", 7))), 409, "STALE_VERSION");
        var failed = ok(admin(keyed(post("/api/v1/invoices/" + eva + "/failure"), Map.of("reason", "Devolució del banc", "at", "2026-09-04", "version", 0))), 200);
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("failureReason").asText()).isEqualTo("Devolució del banc");
        assertThat(failed.path("collections").findValuesAsText("status")).containsExactly("CREATED", "FAILED");
        assertThat(failed.at("/collections/1/failureCode").asText()).isEqualTo("BANK_RETURN");
        assertThat(failed.at("/collections/1/providerRef").asText()).isEqualTo("bill-a-208-1/" + byMember.get("puig").getString("displayNumber"));
        assertThat(events("InvoiceFailed")).singleElement().satisfies(event -> {
            assertThat(event.get("payload", Document.class).getString("provider")).isEqualTo("SEPA_XML");
            assertThat(event.get("payload", Document.class).getString("reason")).isEqualTo("BANK_RETURN");
        });
        // N-10 to the admins with the member, the number, the amount and the admin's reason; never N-35 (not a card).
        outbox.dispatch();
        var notices = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-10")), Document.class, "notifications");
        assertThat(notices).isNotEmpty().allSatisfy(notice -> assertThat(notice.getString("body")).contains("Eva Puig", byMember.get("puig").getString("displayNumber"),
                "60,00", "Devolució del banc"));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-35")), "notifications")).isZero();
        // R-12-16: a FAILED SEPA invoice paid by transfer; R-12-17: a later bank return of a PAID invoice with a SEPA collection.
        var paid = ok(admin(keyed(post("/api/v1/invoices/" + eva + "/payment"), Map.of("paidAt", "2026-09-04", "channel", "TRANSFER", "reference", "TR-1",
                "version", version(eva)))), 200);
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        assertThat(paid.at("/collections/2/providerRef").asText()).isEqualTo("TRANSFER · TR-1");
        assertThat(paid.at("/collections/2/attempt").asInt()).isEqualTo(2);
        var returned = ok(admin(keyed(post("/api/v1/invoices/" + eva + "/failure"), Map.of("reason", "Retorn tardà", "at", "2026-09-04", "version", version(eva)))), 200);
        assertThat(returned.path("status").asText()).isEqualTo("FAILED");
        assertThat(collections(eva)).extracting(c -> c.getString("provider") + " " + c.getString("status") + " " + c.getString("failureCode"))
                .containsExactlyInAnyOrder("SEPA_XML CREATED null", "SEPA_XML FAILED BANK_RETURN", "MANUAL SUCCEEDED null", "SEPA_XML FAILED BANK_RETURN");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("INVOICE_MARKED_FAILED")), "audit_entries")).isEqualTo(2);
        // A PENDING cash invoice has no bank to return it: 409 INVALID_STATE.
        error(admin(keyed(post("/api/v1/invoices/" + id("vila") + "/failure"), Map.of("reason", "x", "at", "2026-09-04", "version", 0))), 409, "INVALID_STATE");
    }

    /**
     * SEPA-04 (b), R-12-17 (owner decision of 13-11-2026): two bank returns of September's remittance marked in one request, all or
     * none: each receipt FAILED with its own FAILED{BANK_RETURN} attempt and audit entry, its InvoiceFailed `summarized`, and the
     * admins get ONE N-10 listing both members and numbers with the summed amount (InvoicesFailed). A receipt of no remittance is
     * refused before anything moves, and the same Idempotency-Key answers the same result.
     */
    @Test void SEPA_04_b_severalReturnsOfARemittanceInOneRequestGiveOneSummaryNotice() throws Exception {
        admin("bill-admin-1", CLUB);
        clock.setInstant(java.time.Instant.parse("2026-09-04T09:00:00Z"));
        String remittance = byMember.get("puig").getString("remittanceId");
        String returns = "/api/v1/remittances/" + remittance + "/returns";
        var both = List.of(id("puig"), id("mas"));
        // A cash receipt is in no remittance: the whole request is refused and nothing moves.
        error(admin(keyed(post(returns), Map.of("invoiceIds", List.of(id("puig"), id("vila")), "reason", "Devolució del banc", "at", "2026-09-04"))), 400, "VALIDATION_ERROR");
        error(admin(keyed(post(returns), Map.of("invoiceIds", both, "reason", "Devolució del banc", "at", "2026-09-05"))), 400, "VALIDATION_ERROR");
        assertThat(both).allSatisfy(id -> assertThat(stored(id).getString("status")).isEqualTo("COLLECTING"));
        String key = UUID.randomUUID().toString();
        var body = mapper.writeValueAsString(Map.of("invoiceIds", both, "reason", "Devolució del banc", "at", "2026-09-04"));
        var result = ok(admin(post(returns).header("Idempotency-Key", key).contentType("application/json").content(body)), 200);
        assertThat(result.path("returned").asInt()).isEqualTo(2);
        assertThat(result.path("invoices")).hasSize(2).allSatisfy(invoice -> {
            assertThat(invoice.path("status").asText()).isEqualTo("FAILED");
            assertThat(invoice.path("failureReason").asText()).isEqualTo("Devolució del banc");
        });
        assertThat(ok(admin(post(returns).header("Idempotency-Key", key).contentType("application/json").content(body)), 200)).isEqualTo(result);
        for (String id : both) {
            assertThat(collections(id)).extracting(c -> c.getString("status") + " " + c.getString("failureCode")).containsExactly("CREATED null", "FAILED BANK_RETURN");
            assertThat(stored(id).getBoolean("includeInNextRun")).isFalse();
        }
        assertThat(events("InvoiceFailed")).hasSize(2).allSatisfy(event -> assertThat(event.get("payload", Document.class).getBoolean("summarized")).isTrue());
        assertThat(events("InvoicesFailed")).singleElement().satisfies(event -> {
            assertThat(event.get("payload", Document.class).getString("remittanceId")).isEqualTo(remittance);
            assertThat(event.get("payload", Document.class).getList("invoiceIds", String.class)).containsExactlyElementsOf(both);
        });
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("INVOICE_MARKED_FAILED")), "audit_entries")).isEqualTo(2);
        outbox.dispatch();
        var notices = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-10")), Document.class, "notifications");
        long total = byMember.get("puig").get("total", Document.class).getLong("amountMinor") + byMember.get("mas").get("total", Document.class).getLong("amountMinor");
        String sum = String.format(java.util.Locale.ROOT, "%d,%02d", total / 100, total % 100);
        assertThat(notices).singleElement().satisfies(notice -> assertThat(notice.getString("body")).contains("Eva Puig", "Mia Mas",
                byMember.get("puig").getString("displayNumber"), byMember.get("mas").getString("displayNumber"), sum, "Devolució del banc"));
        // A second request on returns already marked is refused whole (409), with no notice.
        error(admin(keyed(post(returns), Map.of("invoiceIds", both, "reason", "Devolució del banc", "at", "2026-09-04"))), 409, "INVALID_STATE");
    }

    /**
     * SEPA-04 (b), R-12-17/19: a periodic receipt returned with `includeInNextRun` waits FAILED for the next run, whose remittance
     * presents it again (attempt 2, the member's current mandate, COLLECTING; the simulation counts it); one returned without it
     * waits for none. A rollback of that run puts it back FAILED in September's remittance with the flag, so the next run takes it
     * again; returned again from the new remittance without the flag, it waits for nothing.
     */
    @Test void SEPA_04_b_aReturnedPeriodicReceiptReEntersTheNextRemittanceAndARollbackPutsItBack() throws Exception {
        clock.setInstant(java.time.Instant.parse("2026-09-10T09:00:00Z"));
        String september = byMember.get("puig").getString("remittanceId"), puig = id("puig"), mas = id("mas");
        // Only a periodic receipt can wait for the next remittance: a manual one with the flag → 400.
        var manual = ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "torres", "note", "Ajust", "lines", List.of(Map.of("description", "Ajust",
                "base", Map.of("amountMinor", 1000, "currency", "EUR"), "taxPercent", 0))))), 201);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(manual.path("id").asText())), new org.springframework.data.mongodb.core.query.Update()
                .set("status", "COLLECTING").set("remittanceId", september), "invoices");
        error(admin(keyed(post("/api/v1/invoices/" + manual.path("id").asText() + "/failure"), Map.of("reason", "Devolució", "at", "2026-09-10",
                "version", version(manual.path("id").asText()), "includeInNextRun", true))), 400, "VALIDATION_ERROR");
        ok(admin(keyed(post("/api/v1/remittances/" + september + "/returns"), Map.of("invoiceIds", List.of(puig), "reason", "Fons insuficients",
                "at", "2026-09-10", "includeInNextRun", true))), 200);
        ok(admin(keyed(post("/api/v1/invoices/" + mas + "/failure"), Map.of("reason", "Mandat anul·lat", "at", "2026-09-10", "version", version(mas)))), 200);
        assertThat(stored(puig).getBoolean("includeInNextRun")).isTrue();
        assertThat(stored(mas).getBoolean("includeInNextRun")).isFalse();
        var simulation = simulate("2026-10");
        assertThat(simulation.path("invoicesPreview").findValuesAsText("memberId")).contains("puig");
        var october = run("2026-10", simulation.path("id").asText());
        String octoberRemittance = october.at("/remittance/id").asText(), octoberRun = october.at("/run/id").asText();
        var presented = stored(puig);
        assertThat(presented.getString("status")).isEqualTo("COLLECTING");
        assertThat(presented.getString("remittanceId")).isEqualTo(octoberRemittance);
        assertThat(presented.getString("runId")).isEqualTo(byMember.get("puig").getString("runId"));
        Document attempt = collections(puig).getLast();
        assertThat(attempt.getString("remittanceId")).isEqualTo(octoberRemittance);
        assertThat(attempt.getInteger("attempt")).isEqualTo(2);
        assertThat(attempt.getString("status")).isEqualTo("CREATED");
        assertThat(attempt.getString("mandateRef")).isEqualTo("bill-a-208-1");
        assertThat(stored(mas).getString("status")).isEqualTo("FAILED");
        assertThat(stored(mas).getString("remittanceId")).isEqualTo(september);
        // R-12-14: the rollback puts it back where it was, FAILED in September's remittance, still waiting.
        ok(admin(keyed(post("/api/v1/billing/runs/" + octoberRun + "/rollback"), Map.of("reason", "Preu equivocat", "confirmation", "RETROCEDIR"))), 200);
        var back = stored(puig);
        assertThat(back.getString("status")).isEqualTo("FAILED");
        assertThat(back.getString("remittanceId")).isEqualTo(september);
        assertThat(back.getBoolean("includeInNextRun")).isTrue();
        assertThat(back.getString("failureReason")).isEqualTo("Fons insuficients");
        Document rolledBack = collections(puig).getLast();
        assertThat(rolledBack.getString("status")).isEqualTo("FAILED");
        assertThat(rolledBack.getString("failureCode")).isEqualTo("ROLLBACK");
        assertThat(rolledBack.getString("remittanceId")).isEqualTo(octoberRemittance);
        // Presented again by the next run, then returned again without the flag: it waits for no remittance.
        var again = run("2026-10", simulate("2026-10").path("id").asText());
        String againRemittance = again.at("/remittance/id").asText();
        assertThat(stored(puig).getString("remittanceId")).isEqualTo(againRemittance);
        ok(admin(keyed(post("/api/v1/remittances/" + againRemittance + "/returns"), Map.of("invoiceIds", List.of(puig), "reason", "Fons insuficients", "at", "2026-09-10"))), 200);
        assertThat(stored(puig).getString("status")).isEqualTo("FAILED");
        assertThat(stored(puig).getBoolean("includeInNextRun")).isFalse();
        try (var tenant = TenantContext.open(CLUB)) { assertThat(invoiceRepository.forNextRun()).extracting(Invoice::id).doesNotContain(puig, mas); }
    }

    /**
     * SEPA-04 (b), review: a periodic receipt that P5i settled (its September debit SUCCEEDED, the receipt PAID) and the bank
     * returned later re-enters October's remittance; that earlier, returned debit is not one of October's, so October's run can
     * still be rolled back (R-12-14), and the rollback puts the receipt back FAILED in September's remittance.
     */
    @Test void SEPA_04_b_aRunThatRePresentsAPaidThenReturnedReceiptCanStillBeRolledBack() throws Exception {
        clock.setInstant(java.time.Instant.parse("2026-09-10T09:00:00Z"));
        String september = byMember.get("puig").getString("remittanceId"), puig = id("puig");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(collections(puig).getFirst().get("_id"))),
                new org.springframework.data.mongodb.core.query.Update().set("status", "SUCCEEDED"), "collections");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(puig)), new org.springframework.data.mongodb.core.query.Update().set("status", "PAID")
                .set("paidAt", java.util.Date.from(java.time.Instant.parse("2026-08-31T22:00:00Z"))), "invoices");
        ok(admin(keyed(post("/api/v1/remittances/" + september + "/returns"), Map.of("invoiceIds", List.of(puig), "reason", "Fons insuficients",
                "at", "2026-09-10", "includeInNextRun", true))), 200);
        var october = run("2026-10", simulate("2026-10").path("id").asText());
        String octoberRun = october.at("/run/id").asText();
        assertThat(stored(puig).getString("remittanceId")).isEqualTo(october.at("/remittance/id").asText());
        assertThat(ok(admin(get("/api/v1/billing/periods/2026-10")), 200).at("/run/rollbackable").asBoolean()).isTrue();
        ok(admin(keyed(post("/api/v1/billing/runs/" + octoberRun + "/rollback"), Map.of("reason", "Preu equivocat", "confirmation", "RETROCEDIR"))), 200);
        assertThat(stored(puig).getString("status")).isEqualTo("FAILED");
        assertThat(stored(puig).getString("remittanceId")).isEqualTo(september);
        assertThat(stored(puig).getBoolean("includeInNextRun")).isTrue();
    }

    @Test @AuditCovers({AuditAction.INVOICE_CREATED_MANUAL, AuditAction.INVOICE_CANCELLED})
    void T_12_18_aManualAdjustmentIsNumberedInSequenceAndOnlyPendingOrFailedInvoicesAreCancelled() throws Exception {
        var adjustment = ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "serra-laura", "lines", List.of(Map.of("description", "Ajust quota setembre",
                "base", Map.of("amountMinor", -3000, "currency", "EUR"), "taxPercent", 0)), "includeInNextRun", false, "note", "Quota cobrada de més"))), 201);
        assertThat(adjustment.path("displayNumber").asText()).isEqualTo("2026-0920");
        assertThat(adjustment.path("kind").asText()).isEqualTo("MANUAL");
        assertThat(adjustment.path("status").asText()).isEqualTo("PENDING");
        assertThat(adjustment.path("runId").isNull()).isTrue();
        assertThat(adjustment.at("/lines/0/origin").asText()).isEqualTo("ADJUSTMENT");
        assertThat(adjustment.at("/lines/0/description").asText()).isEqualTo("Ajust quota setembre");
        assertThat(adjustment.at("/total/amountMinor").asLong()).isEqualTo(-3000);
        assertThat(adjustment.at("/paymentMethod/type").asText()).isEqualTo("SEPA_DD");
        assertThat(adjustment.at("/paymentMethod/maskedAccount").asText()).endsWith("0211").doesNotContain(IBAN);
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("INVOICE_CREATED_MANUAL")), Document.class, "audit_entries"))
                .singleElement().satisfies(entry -> assertThat(entry.getString("reason")).isEqualTo("Quota cobrada de més"));
        assertThat(events("InvoiceIssued")).hasSize(9);
        // A line in another currency is refused; a run holding the billing lock makes the counter wait (409 BILLING_BUSY).
        error(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "puig", "lines", List.of(Map.of("description", "x", "base", Map.of("amountMinor", 100, "currency", "USD"),
                "taxPercent", 0)), "note", "x"))), 422, "CURRENCY_MISMATCH");
        mongo.insert(new Document("_id", CLUB + ":billing").append("clubId", CLUB).append("holder", "run:other").append("acquiredAt", java.util.Date.from(NOW))
                .append("expiresAt", java.util.Date.from(NOW.plusSeconds(600))), "billing_locks");
        error(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "puig", "lines", List.of(Map.of("description", "x", "base", Map.of("amountMinor", 100, "currency", "EUR"),
                "taxPercent", 0)), "note", "x"))), 409, "BILLING_BUSY");
        mongo.remove(Query.query(Criteria.where("_id").is(CLUB + ":billing")), "billing_locks");
        // R-12-19: a COLLECTING (remitted) invoice is never cancelled; a PENDING one is, with the admin's reason.
        error(admin(keyed(post("/api/v1/invoices/" + id("puig") + "/cancellation"), Map.of("reason", "Duplicat", "version", 0))), 409, "INVALID_STATE");
        var cancelled = ok(admin(keyed(post("/api/v1/invoices/" + adjustment.path("id").asText() + "/cancellation"), Map.of("reason", "Ajust equivocat", "version", 0))), 200);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("cancelReason").asText()).isEqualTo("Ajust equivocat");
        assertThat(events("InvoiceCancelled")).singleElement().satisfies(event -> assertThat(event.get("payload", Document.class).getString("reason")).isEqualTo("ADMIN"));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("INVOICE_CANCELLED")), "audit_entries")).isEqualTo(1);
        // A paid one is not cancelled either.
        ok(admin(keyed(post("/api/v1/invoices/" + id("vila") + "/payment"), Map.of("paidAt", "2026-08-25", "channel", "BIZUM", "version", 0))), 200);
        error(admin(keyed(post("/api/v1/invoices/" + id("vila") + "/cancellation"), Map.of("reason", "x", "version", version(id("vila"))))), 409, "INVALID_STATE");
        // The D6 list: newest number first, q over the number, the chips as status filters, concept with the line count.
        var page = ok(admin(get("/api/v1/invoices").param("sort", "number,desc")), 200);
        assertThat(page.path("items").findValuesAsText("displayNumber")).first().isEqualTo("2026-0920");
        // A manual invoice bills its issue month; September's chip lists the run's eight.
        assertThat(ok(admin(get("/api/v1/invoices").param("filter", "period:eq:2026-09")), 200).path("totalItems").asLong()).isEqualTo(8);
        assertThat(ok(admin(get("/api/v1/invoices").param("q", "0914")), 200).path("items").findValuesAsText("displayNumber")).containsExactly("2026-0914");
        assertThat(ok(admin(get("/api/v1/invoices").param("filter", "status:eq:COLLECTING")), 200).path("totalItems").asLong()).isEqualTo(4);
        var vila = ok(admin(get("/api/v1/invoices").param("filter", "memberId:eq:vila").param("fields", "displayNumber,concept,member,paymentMethodType")), 200).at("/items/0");
        assertThat(vila.path("concept").asText()).isEqualTo("Quota Abonat — Setembre 2026 (+3)");
        assertThat(vila.at("/member/fullName").asText()).isEqualTo("Joan Vila");
        assertThat(vila.path("paymentMethodType").asText()).isEqualTo("MANUAL");
        assertThat(ok(admin(get("/api/v1/invoices").param("sort", "memberLastName,asc")), 200).path("items").findValuesAsText("displayNumber")).first().isEqualTo("2026-0912");
    }

    /**
     * E11-T15 step 11 (the audit of 21-10, F11; R-12-19, R-12-26): an adjustment for December created on 28-11 is December's —
     * D6's December list and December's accounting export — not November's. Without `period` it is the issue month, as before; at
     * most three months either side of it.
     */
    @Test void R_12_19_R_12_26_aManualReceiptCarriesTheMonthItIsFor() throws Exception {
        clock.setInstant(java.time.Instant.parse("2026-11-28T09:00:00Z"));
        var lines = List.of(Map.of("description", "Ajust quota desembre", "base", Map.of("amountMinor", 1500, "currency", "EUR"), "taxPercent", 0));
        var december = ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "vila", "period", "2026-12", "lines", lines, "note", "Ajust de desembre"))), 201);
        assertThat(december.path("period").asText()).isEqualTo("2026-12");
        assertThat(december.path("issueDate").asText()).isEqualTo("2026-11-28");
        assertThat(mongo.findById(december.path("id").asText(), Document.class, "invoices").getString("period")).isEqualTo("2026-12");
        var listed = new ArrayList<String>();
        ok(admin(get("/api/v1/invoices").param("filter", "period:eq:2026-12")), 200).path("items").forEach(item -> listed.add(item.path("id").asText()));
        assertThat(listed).containsExactly(december.path("id").asText());
        var export = call(admin(get("/api/v1/billing/exports").param("period", "2026-12")));
        assertThat(export.getStatus()).isEqualTo(200);
        assertThat(export.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains(december.path("displayNumber").asText(), "Ajust quota desembre");
        assertThat(call(admin(get("/api/v1/billing/exports").param("period", "2026-11"))).getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain(december.path("displayNumber").asText());
        // Without a period: the issue month, as before.
        var november = ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "vila", "lines", lines, "note", "Ajust"))), 201);
        assertThat(november.path("period").asText()).isEqualTo("2026-11");
        // Three months either side of the issue month; anything else, or not a month, is 400 on `period` and takes no number.
        assertThat(ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "vila", "period", "2026-08", "lines", lines, "note", "Ajust"))), 201)
                .path("period").asText()).isEqualTo("2026-08");
        assertThat(ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "vila", "period", "2027-02", "lines", lines, "note", "Ajust"))), 201)
                .path("period").asText()).isEqualTo("2027-02");
        long before = invoices().size();
        for (String period : List.of("2026-07", "2027-03", "2026-13", "desembre")) {
            var refused = call(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "vila", "period", period, "lines", lines, "note", "Ajust"))));
            assertThat(refused.getStatus()).as(period + " " + refused.getContentAsString()).isEqualTo(400);
            assertThat(json(refused).path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(json(refused).at("/details/fieldErrors/0/field").asText()).as(period).isEqualTo("period");
        }
        assertThat(invoices()).hasSize((int) before);
    }

    @Test void T_12_12_anIssuedInvoiceHasNoPatchItsRepositoryNeverReplacesItAndCollectionsAreAppendOnly() throws Exception {
        error(admin(patch("/api/v1/invoices/" + id("puig")).contentType("application/json").content("{\"total\":{\"amountMinor\":6000,\"currency\":\"EUR\"}}")), 405, "METHOD_NOT_ALLOWED");
        try (var tenant = TenantContext.open(CLUB)) {
            Invoice invoice = invoiceRepository.findById(id("puig")).orElseThrow();
            assertThatThrownBy(() -> invoiceRepository.replace(invoice)).isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("R-12-10");
            assertThatThrownBy(() -> invoiceRepository.deleteById(invoice.id())).isInstanceOf(UnsupportedOperationException.class);
            Collection collection = collectionRepository.forInvoice(invoice.id()).getFirst();
            assertThatThrownBy(() -> collectionRepository.replace(collection)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> collectionRepository.deleteById(collection.id())).isInstanceOf(UnsupportedOperationException.class);
        }
        // The state actions move only the state fields: lines, amounts, member and number stay as issued.
        var before = stored(id("vila"));
        ok(admin(keyed(post("/api/v1/invoices/" + id("vila") + "/payment"), Map.of("paidAt", "2026-08-25", "channel", "CASH", "version", 0))), 200);
        var after = stored(id("vila"));
        for (String field : List.of("lines", "total", "base", "tax", "memberId", "series", "number", "displayNumber", "memberSnapshot", "paymentMethod", "issueDate")) {
            assertThat(after.get(field)).as(field).isEqualTo(before.get(field));
        }
        assertThat(((Number) after.get("version")).longValue()).isEqualTo(1);
    }

    @Test void T_12_20_aMemberReadsTheirOwnReceiptsAndTheirFamilyHoldersWithThePdfAndNeverAnIban() throws Exception {
        var joan = ok(as(get("/api/v1/me/invoices"), CLUB, "MEMBER", "serra-joan"), 200);
        assertThat(joan.path("items")).singleElement().satisfies(item -> {
            assertThat(item.path("id").asText()).isEqualTo(id("serra-laura"));
            assertThat(item.path("familyGroup").asBoolean()).isTrue();
            assertThat(item.at("/total/amountMinor").asLong()).isEqualTo(9000);
            assertThat(item.at("/lines/0/description").asText()).isEqualTo("Quota Abonat 2 gossos — Setembre 2026");
        });
        assertThat(joan.path("totalItems").asLong()).isEqualTo(1);
        var laura = ok(as(get("/api/v1/me/invoices"), CLUB, "MEMBER", "serra-laura"), 200);
        assertThat(laura.path("items")).singleElement().satisfies(item -> assertThat(item.path("familyGroup").asBoolean()).isFalse());
        assertThat(ok(as(get("/api/v1/me/invoices"), CLUB, "MEMBER", "roca"), 200).path("items")).isEmpty();
        assertThat(ok(as(get("/api/v1/me/invoices/" + id("serra-laura")), CLUB, "MEMBER", "serra-joan"), 200).path("familyGroup").asBoolean()).isTrue();
        error(as(get("/api/v1/me/invoices/" + id("serra-laura")), CLUB, "MEMBER", "roca"), 404, "NOT_FOUND");
        error(as(get("/api/v1/me/invoices/" + id("puig")), CLUB, "MEMBER", "serra-joan"), 404, "NOT_FOUND");
        // FAMILY_GROUP off: everyone reads only their own.
        var modules = new ArrayList<>(List.of(Module.values())); modules.remove(Module.FAMILY_GROUP);
        modules(CLUB, modules);
        assertThat(ok(as(get("/api/v1/me/invoices"), CLUB, "MEMBER", "serra-joan"), 200).path("items")).isEmpty();
        modules(CLUB, List.of(Module.values()));
        // The receipt PDF: the club's name, the frozen description, the masked account, never the IBAN.
        var pdf = call(as(get("/api/v1/me/invoices/" + id("serra-laura") + "/document").header("Accept-Language", "ca"), CLUB, "MEMBER", "serra-joan"));
        assertThat(pdf.getStatus()).isEqualTo(200);
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(pdf.getHeader("Content-Disposition")).contains(byMember.get("serra-laura").getString("displayNumber") + ".pdf");
        String text;
        try (var document = Loader.loadPDF(pdf.getContentAsByteArray())) { text = new PDFTextStripper().getText(document); }
        assertThat(text).contains("Club Agility Facturació", "Rebut", byMember.get("serra-laura").getString("displayNumber"), "Quota Abonat 2 gossos — Setembre 2026",
                "Laura Serra", "···· 0211").doesNotContain(IBAN);
        var adminPdf = call(admin(get("/api/v1/invoices/" + id("puig") + "/document")));
        assertThat(adminPdf.getStatus()).isEqualTo(200);
        try (var document = Loader.loadPDF(adminPdf.getContentAsByteArray())) { assertThat(new PDFTextStripper().getText(document)).contains("Eva Puig", "Rebut"); }
        // The drawer's detail: collections oldest first, masked method, no IBAN.
        JsonNode detail = ok(admin(get("/api/v1/invoices/" + id("puig"))), 200);
        assertThat(detail.path("collections")).hasSize(1);
        assertThat(detail.toString()).doesNotContain(IBAN);
    }

    /** Round 2 (review #9): S12 §10, the receipt names the tax after the club's country profile (`ES` → «IVA»). */
    @Test void T_12_20_theReceiptNamesTheTaxAfterTheClubsCountryProfile() throws Exception {
        String taxed = ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "puig", "lines", List.of(Map.of("description", "Material",
                "base", Map.of("amountMinor", 4959, "currency", "EUR"), "taxPercent", 21)), "note", "Amb IVA"))), 201).path("id").asText();
        assertThat(receiptText(taxed)).contains("IVA", "10,41", "60,00").doesNotContain("Impost");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new org.springframework.data.mongodb.core.query.Update().set("countryProfile", "GENERIC"), "clubs");
        configs.invalidate(CLUB);
        assertThat(receiptText(taxed)).contains("Impost", "10,41").doesNotContain("IVA");
        // The Cànic bills without tax (B3): no tax row at all.
        assertThat(receiptText(id("vila"))).doesNotContain("Impost", "IVA");
    }
    private String receiptText(String invoiceId) throws Exception {
        var pdf = call(admin(get("/api/v1/invoices/" + invoiceId + "/document")));
        assertThat(pdf.getStatus()).isEqualTo(200);
        try (var document = Loader.loadPDF(pdf.getContentAsByteArray())) { return new PDFTextStripper().getText(document); }
    }

    @Test void T_12_21_anotherClubsAdminGets404AndMembersInstructorsAndImpersonationAre403() throws Exception {
        String invoice = id("puig"), run = byMember.get("puig").getString("runId");
        for (String path : List.of("/api/v1/invoices/" + invoice, "/api/v1/invoices/" + invoice + "/document", "/api/v1/billing/runs/" + run)) {
            error(as(get(path), OTHER, "ADMIN", null), 404, "NOT_FOUND");
        }
        error(as(keyed(post("/api/v1/invoices/" + invoice + "/payment"), Map.of("paidAt", "2026-08-25", "channel", "CASH", "version", 0)), OTHER, "ADMIN", null), 404, "NOT_FOUND");
        error(as(keyed(post("/api/v1/billing/runs/" + run + "/rollback"), Map.of("reason", "x", "confirmation", "RETROCEDIR")), OTHER, "ADMIN", null), 404, "NOT_FOUND");
        // Club B's month is its own: empty.
        assertThat(ok(as(get("/api/v1/billing/periods/2026-09"), OTHER, "ADMIN", null), 200).path("counts").path("all").asLong()).isZero();
        assertThat(ok(as(get("/api/v1/invoices"), OTHER, "ADMIN", null), 200).path("totalItems").asLong()).isZero();
        for (String role : List.of("MEMBER", "INSTRUCTOR")) {
            error(as(get("/api/v1/invoices"), CLUB, role, "puig"), 403, "FORBIDDEN");
            error(as(get("/api/v1/billing/periods/2026-09"), CLUB, role, "puig"), 403, "FORBIDDEN");
            error(as(get("/api/v1/billing/runs/" + run), CLUB, role, "puig"), 403, "FORBIDDEN");
        }
        assertThat(stored(invoice).getString("status")).isEqualTo("COLLECTING");
    }

    @Test void T_12_32_twoPaymentsWithTheSameKeyPayOnce() throws Exception {
        String key = UUID.randomUUID().toString(), vila = id("vila");
        String body = mapper.writeValueAsString(Map.of("paidAt", "2026-08-25", "channel", "CASH", "version", 0));
        var first = call(admin(post("/api/v1/invoices/" + vila + "/payment").header("Idempotency-Key", key).contentType("application/json").content(body)));
        var second = call(admin(post("/api/v1/invoices/" + vila + "/payment").header("Idempotency-Key", key).contentType("application/json").content(body)));
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(collections(vila)).filteredOn(c -> "SUCCEEDED".equals(c.getString("status"))).hasSize(1);
        assertThat(events("InvoicePaid")).hasSize(1);
        // Another key with the old version is a stale request.
        error(admin(keyed(post("/api/v1/invoices/" + vila + "/payment"), Map.of("paidAt", "2026-08-25", "channel", "CASH", "version", 0))), 409, "STALE_VERSION");
    }

    /** E8-T02 (E8-T01 question 4, ruling E85): D10's «Rebuts recents» reads S12's invoices — `issueDate`, `total`, newest first. */
    @Test void D10_theMembersRecentReceiptsComeFromTheIssuedInvoices() throws Exception {
        ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "puig", "lines", List.of(Map.of("description", "Ajust", "base",
                Map.of("amountMinor", 500, "currency", "EUR"), "taxPercent", 0)), "note", "Ajust"))), 201);
        var overview = ok(admin(get("/api/v1/members/puig/overview")), 200);
        assertThat(overview.path("invoicesCount").asInt()).isEqualTo(2);
        assertThat(overview.at("/recentInvoices/0/id").asText()).isNotEqualTo(id("puig"));
        assertThat(overview.at("/recentInvoices/0/amount/amountMinor").asLong()).isEqualTo(500);
        assertThat(overview.at("/recentInvoices/1/id").asText()).isEqualTo(id("puig"));
        assertThat(overview.at("/recentInvoices/1/date").asText()).isEqualTo("2026-08-25");
        assertThat(overview.at("/recentInvoices/1/amount/amountMinor").asLong()).isEqualTo(6000);
        assertThat(overview.at("/recentInvoices/1/status").asText()).isEqualTo("COLLECTING");
    }

    @Test void R_12_25_theMembersPendingChargesAreListedBilledOrNot() throws Exception {
        var charges = ok(admin(get("/api/v1/members/mas/pending-charges")), 200);
        assertThat(charges).hasSize(2).allSatisfy(charge -> {
            assertThat(charge.path("invoiceId").asText()).isEqualTo(id("mas"));
            assertThat(charge.at("/amount/amountMinor").asLong()).isEqualTo(1200);
        });
        error(as(get("/api/v1/members/mas/pending-charges"), OTHER, "ADMIN", null), 404, "NOT_FOUND");
        var modules = new ArrayList<>(List.of(Module.values())); modules.remove(Module.SINGLE_CLASS);
        modules(CLUB, modules);
        error(admin(get("/api/v1/members/mas/pending-charges")), 404, "MODULE_DISABLED");
    }
}

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
        assertThat(failed.at("/collections/1/providerRef").asText()).isEqualTo("bill-a-mandate/" + byMember.get("puig").getString("displayNumber"));
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
        assertThat(adjustment.at("/paymentMethod/maskedAccount").asText()).endsWith("4321").doesNotContain(IBAN);
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
                "Laura Serra", "···· 4321").doesNotContain(IBAN);
        var adminPdf = call(admin(get("/api/v1/invoices/" + id("puig") + "/document")));
        assertThat(adminPdf.getStatus()).isEqualTo(200);
        try (var document = Loader.loadPDF(adminPdf.getContentAsByteArray())) { assertThat(new PDFTextStripper().getText(document)).contains("Eva Puig", "Rebut"); }
        // The drawer's detail: collections oldest first, masked method, no IBAN.
        JsonNode detail = ok(admin(get("/api/v1/invoices/" + id("puig"))), 200);
        assertThat(detail.path("collections")).hasSize(1);
        assertThat(detail.toString()).doesNotContain(IBAN);
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

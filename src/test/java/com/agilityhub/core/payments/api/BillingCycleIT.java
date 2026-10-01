package com.agilityhub.core.payments.api;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * S12 WP-12-B, the monthly cycle over the fictional census (E8-T02): the simulation (R-12-07), the run (R-12-08/09/11/12),
 * its rollback (R-12-14), the concurrency and idempotency of the run (R-12-29), the club's time zone (R-12-30) and the
 * collection-day parameter (R-12-12) — T-12-09, T-12-10, T-12-13, T-12-23, T-12-29, T-12-29b, and T-12-32's MANUAL-only club.
 */
class BillingCycleIT extends BillingItSupport {
    static final List<String> ORDER = List.of("mas", "puig", "serra-laura", "torres", "vidal", "vila", "vives", "xirau");

    @Test void T_12_09_theSimulationListsTheSixIncidentsTheCashMembersThePreviewAndTheKpisAndKeepsOnePerMonth() throws Exception {
        var simulation = simulate("2026-09");
        assertThat(simulation.path("incidents").findValuesAsText("code")).containsExactly("NO_BANK_ACCOUNT", "NO_PLAN", "CURRENCY_MISMATCH", "NO_PRICE",
                "PROVIDER_DISABLED", "CARD_INVALID");
        assertThat(simulation.path("incidents").findValuesAsText("memberName")).containsExactly("Bet Bosch", "Carles Camps", "Fina Font", "Pau Riera", "Sara Sala", "Pere Soler");
        assertThat(simulation.path("cashMembers").findValuesAsText("memberId")).containsExactlyInAnyOrder("vidal", "vila", "vives", "xirau");
        assertThat(simulation.at("/cashMembers/0/plannedLeaveDate").isNull()).isTrue();
        assertThat(simulation.path("invoicesPreview").findValuesAsText("memberId")).containsExactlyElementsOf(ORDER);
        var kpis = simulation.path("kpis");
        assertThat(kpis.path("count").asInt()).isEqualTo(8);
        assertThat(kpis.at("/total/amountMinor").asLong()).isEqualTo(116_400);
        assertThat(kpis.at("/byProvider/SEPA_XML/count").asInt()).isEqualTo(4);
        assertThat(kpis.at("/byProvider/SEPA_XML/total/amountMinor").asLong()).isEqualTo(20_400);
        assertThat(kpis.at("/byProvider/MANUAL/total/amountMinor").asLong()).isEqualTo(96_000);
        assertThat(kpis.path("byProvider").has("STRIPE")).isFalse();
        assertThat(kpis.path("cashPending").asInt()).isEqualTo(4);
        assertThat(kpis.at("/inactivityFees/firstMonth/amountMinor").asLong()).isEqualTo(2000);
        // Laura pays the family fare; her family member gets no invoice (R-12-04); Mia's single classes; Vila's half-year (R-12-05).
        var laura = preview(simulation, "serra-laura");
        assertThat(laura.at("/total/amountMinor").asLong()).isEqualTo(9000);
        assertThat(laura.at("/lines/0/description").asText()).isEqualTo("Quota Abonat 2 gossos — Setembre 2026");
        assertThat(preview(simulation, "mas").path("lines").findValuesAsText("description")).containsExactly("Classe 04/08 — Duna", "Classe 11/08 — Duna");
        assertThat(preview(simulation, "vila").path("lines").findValuesAsText("description")).containsExactly("Quota Abonat — Setembre 2026",
                "Quota Abonat — Octubre 2026", "Quota Abonat — Novembre 2026", "Quota Abonat — Desembre 2026");
        assertThat(preview(simulation, "torres").at("/lines/0/origin").asText()).isEqualTo("MAINTENANCE_FEE");
        // Nothing of business value is written: no invoice, no date moved; one simulation per month, replaced; RemittanceSimulated.
        assertThat(invoices()).isEmpty();
        assertThat(member("puig", "nextInvoiceDate")).isEqualTo("2026-09-01");
        var again = simulate("2026-09");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("period").is("2026-09")), "billing_simulations")).isEqualTo(1);
        assertThat(mongo.findById(again.path("id").asText(), Document.class, "billing_simulations")).isNotNull();
        assertThat(events("RemittanceSimulated")).hasSize(2).last().satisfies(event -> {
            assertThat(event.get("payload", Document.class).getList("incidents", Document.class)).hasSize(6);
            assertThat(event.get("payload", Document.class).get("totals", Document.class).getInteger("count")).isEqualTo(8);
        });
        // S12 §13: a month more than three months ahead is refused; GET /billing/periods shows the simulation of step 1.
        error(admin(post("/api/v1/billing/simulations").contentType("application/json").content("{\"period\":\"2026-12\"}")), 400, "VALIDATION_ERROR");
        var period = ok(admin(get("/api/v1/billing/periods/2026-09")), 200);
        assertThat(period.at("/simulation/id").asText()).isEqualTo(again.path("id").asText());
        assertThat(period.path("run").isNull()).isTrue();
        assertThat(period.path("counts").path("all").asLong()).isZero();
    }
    private static JsonNode preview(JsonNode simulation, String memberId) {
        for (var preview : simulation.path("invoicesPreview")) { if (preview.path("memberId").asText().equals(memberId)) { return preview; } }
        throw new AssertionError(memberId + " not previewed");
    }

    @Test @AuditCovers(AuditAction.REMITTANCE_GENERATED)
    void T_12_09_T_12_29_theRunNumbersTheInvoicesAdvancesOnlyTheIncludedMembersAndAuditsOnce() throws Exception {
        var simulation = simulate("2026-09");
        var result = run("2026-09", simulation.path("id").asText());
        var run = result.path("run");
        assertThat(run.path("status").asText()).isEqualTo("GENERATED");
        assertThat(run.path("rollbackable").asBoolean()).isTrue();
        assertThat(run.path("collectionDate").asText()).isEqualTo("2026-09-01");
        assertThat(result.path("skipped").findValuesAsText("code")).hasSize(6);
        // R-12-08: one consecutive block in the members' order, from the imported counter (T-12-04: 2026-0912…).
        var issued = invoices();
        assertThat(issued).extracting(invoice -> invoice.getString("memberId")).containsExactlyElementsOf(ORDER);
        assertThat(issued).extracting(invoice -> invoice.getString("displayNumber")).containsExactly("2026-0912", "2026-0913", "2026-0914", "2026-0915",
                "2026-0916", "2026-0917", "2026-0918", "2026-0919");
        assertThat(issued).allSatisfy(invoice -> {
            assertThat(invoice.getString("issueDate")).isEqualTo("2026-08-25");
            assertThat(invoice.getString("period")).isEqualTo("2026-09");
            assertThat(invoice.getString("kind")).isEqualTo("PERIODIC");
            assertThat(invoice.toJson()).doesNotContain(IBAN);
        });
        assertThat(issued.subList(0, 4)).extracting(invoice -> invoice.getString("status")).containsOnly("COLLECTING");
        assertThat(issued.subList(4, 8)).extracting(invoice -> invoice.getString("status")).containsOnly("PENDING");
        // One collection per invoice: SEPA attempts in the remittance, manual ones waiting.
        var collections = mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "collections");
        assertThat(collections).hasSize(8).allSatisfy(collection -> assertThat(collection.getString("status")).isEqualTo("CREATED"));
        var remittance = result.path("remittance");
        assertThat(remittance.path("count").asInt()).isEqualTo(4);
        assertThat(remittance.at("/total/amountMinor").asLong()).isEqualTo(20_400);
        assertThat(remittance.path("messageId").asText()).isEqualTo("bill-a-2026-09-1");
        assertThat(remittance.path("requestedCollectionDate").asText()).isEqualTo("2026-09-01");
        assertThat(remittance.at("/creditor/maskedIban").asText()).endsWith("9876").startsWith("····");
        assertThat(result.toString()).doesNotContain(IBAN).doesNotContain(IBAN.replace("4321", "9876"));
        // R-12-06: only the included members' dates move (the pack and the family member included); the skipped ones keep theirs.
        for (String included : List.of("mas", "puig", "roca", "serra-laura", "serra-joan", "torres")) { assertThat(member(included, "nextInvoiceDate")).isEqualTo("2026-10-01"); }
        for (String cash : List.of("vidal", "vila", "vives", "xirau")) { assertThat(member(cash, "nextInvoiceDate")).isEqualTo("2027-01-01"); }
        for (String skipped : List.of("bosch", "camps", "font", "riera", "sala", "soler", "nuria", "pere")) { assertThat(member(skipped, "nextInvoiceDate")).isEqualTo("2026-09-01"); }
        // R-12-25: Mia's charges carry her invoice.
        assertThat(mongo.find(Query.query(Criteria.where("memberId").is("mas")), Document.class, "pending_charges"))
                .allSatisfy(charge -> assertThat(charge.getString("invoiceId")).isEqualTo(issued.getFirst().getString("_id")));
        // Events and the single audit entry (R-14-10).
        assertThat(events("InvoiceIssued")).hasSize(8);
        assertThat(events("InvoiceCollecting")).hasSize(4);
        assertThat(events("RemittanceGenerated")).singleElement().satisfies(event -> assertThat(event.get("payload", Document.class).getList("invoiceIds", String.class)).hasSize(4));
        assertThat(events("BillingRunCreated")).singleElement().satisfies(event -> assertThat(event.get("payload", Document.class).getInteger("invoiceCount")).isEqualTo(8));
        var audit = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("REMITTANCE_GENERATED")), Document.class, "audit_entries");
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.getString("entityId")).isEqualTo(run.path("id").asText());
            assertThat(entry.get("details", Document.class).getList("invoiceIds", String.class)).hasSize(8);
        });
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("REMITTANCE_GENERATED")), "audit_entries")).isEqualTo(1);
        // The same month again: 409 RUN_EXISTS; GET /billing/periods/{period} shows the run, its remittance and the chips.
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", simulation.path("id").asText()))), 409, "RUN_EXISTS");
        var period = ok(admin(get("/api/v1/billing/periods/2026-09")), 200);
        assertThat(period.at("/run/id").asText()).isEqualTo(run.path("id").asText());
        assertThat(period.at("/remittance/fileAvailable").asBoolean()).isFalse();
        assertThat(period.path("counts").path("all").asLong()).isEqualTo(8);
        assertThat(period.path("counts").path("remitted").asLong()).isEqualTo(4);
        assertThat(period.path("counts").path("pending").asLong()).isEqualTo(4);
        assertThat(ok(admin(get("/api/v1/billing/runs/" + run.path("id").asText())), 200).path("invoiceIds")).hasSize(8);
    }

    @Test void T_12_09_aRunNeedsTheMonthsSimulationTakenAfterTheLastRelevantChange() throws Exception {
        var simulation = simulate("2026-09").path("id").asText();
        clock.setInstant(NOW.plusSeconds(60));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("bill-price-abonat-monthly_fee")), new Update().set("updatedAt", clock.instant()), "prices");
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", simulation))), 409, "SIMULATION_STALE");
        var fresh = simulate("2026-09").path("id").asText();
        clock.setInstant(NOW.plusSeconds(120));
        parameter(CLUB, "billing.cashInvoicing", "MONTHLY");
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", fresh))), 409, "SIMULATION_STALE");
        var october = simulate("2026-10").path("id").asText();
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", october))), 409, "SIMULATION_STALE");
        clock.setInstant(NOW.plusSeconds(180));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("puig")), new Update().set("updatedAt", clock.instant()), "members");
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-10", "simulationId", october))), 409, "SIMULATION_STALE");
        assertThat(invoices()).isEmpty();
        assertThat(member("puig", "nextInvoiceDate")).isEqualTo("2026-09-01");
    }

    @Test void T_12_10_twoSimultaneousRunsGiveOne201AndOne409AndTheSameKeyAnswersTheSameRun() throws Exception {
        String simulation = simulate("2026-09").path("id").asText();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<org.springframework.mock.web.MockHttpServletResponse>>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> { start.await(); return call(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", simulation)))); }));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>(); var codes = new ArrayList<String>();
            for (var future : futures) {
                var response = future.get(60, TimeUnit.SECONDS);
                statuses.add(response.getStatus());
                if (response.getStatus() == 409) { codes.add(json(response).path("code").asText()); }
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(codes).singleElement().isIn("BILLING_BUSY", "RUN_EXISTS");
        } finally { pool.shutdownNow(); }
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "billing_runs")).isEqualTo(1);
        assertThat(invoices()).hasSize(8);
        // Same Idempotency-Key and body → the stored 201, the same run; nothing is generated twice.
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "billing_runs");
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "invoices");
        String key = UUID.randomUUID().toString();
        var body = mapper.writeValueAsString(Map.of("period", "2026-10", "simulationId", simulate("2026-10").path("id").asText()));
        var first = call(admin(post("/api/v1/billing/runs").header("Idempotency-Key", key).contentType("application/json").content(body)));
        var second = call(admin(post("/api/v1/billing/runs").header("Idempotency-Key", key).contentType("application/json").content(body)));
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(201);
        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(json(second).at("/run/id").asText()).isEqualTo(json(first).at("/run/id").asText());
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("period").is("2026-10")), "billing_runs")).isEqualTo(1);
        // A held billing lock answers BILLING_BUSY to a simulation and a run.
        mongo.insert(new Document("_id", CLUB + ":billing").append("clubId", CLUB).append("holder", "run:other").append("acquiredAt", java.util.Date.from(NOW))
                .append("expiresAt", java.util.Date.from(NOW.plusSeconds(600))), "billing_locks");
        error(admin(post("/api/v1/billing/simulations").contentType("application/json").content("{\"period\":\"2026-11\"}")), 409, "BILLING_BUSY");
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-11", "simulationId", "missing"))), 404, "NOT_FOUND");
        // A lease that ran out is taken over (the TTL monitor may not have swept it yet).
        clock.setInstant(NOW.plusSeconds(601));
        assertThat(simulate("2026-11").path("period").asText()).isEqualTo("2026-11");
    }

    @Test @AuditCovers(AuditAction.REMITTANCE_ROLLED_BACK)
    void T_12_13_aRollbackGivesBackNumbersDatesAndChargesAndASecondRunReproducesTheNumbers() throws Exception {
        var first = run("2026-09", simulate("2026-09").path("id").asText());
        String runId = first.at("/run/id").asText();
        var numbers = numbersByMember();
        error(admin(keyed(post("/api/v1/billing/runs/" + runId + "/rollback"), Map.of("reason", "Preu equivocat", "confirmation", "retrocedir"))), 400, "VALIDATION_ERROR");
        var rollback = ok(admin(keyed(post("/api/v1/billing/runs/" + runId + "/rollback"), Map.of("reason", "Preu equivocat", "confirmation", "RETROCEDIR"))), 200);
        assertThat(rollback.path("cancelledInvoices").asInt()).isEqualTo(8);
        assertThat(rollback.path("restoredMembers").asInt()).isEqualTo(10);
        assertThat(invoices()).allSatisfy(invoice -> {
            assertThat(invoice.getString("status")).isEqualTo("CANCELLED");
            assertThat(invoice.getString("cancelReason")).isEqualTo("ROLLBACK");
        });
        var failed = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("status").is("FAILED")), Document.class, "collections");
        assertThat(failed).hasSize(8).allSatisfy(collection -> assertThat(collection.getString("failureCode")).isEqualTo("ROLLBACK"));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "collections")).isEqualTo(16);
        var remittance = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "remittances");
        assertThat(remittance.getString("status")).isEqualTo("ROLLED_BACK");
        for (String member : List.of("mas", "puig", "roca", "serra-laura", "serra-joan", "torres", "vidal", "vila", "vives", "xirau")) {
            assertThat(member(member, "nextInvoiceDate")).as(member).isEqualTo("2026-09-01");
        }
        assertThat(mongo.find(Query.query(Criteria.where("memberId").is("mas")), Document.class, "pending_charges")).allSatisfy(charge -> assertThat(charge.get("invoiceId")).isNull());
        assertThat(events("RemittanceRolledBack")).hasSize(1);
        assertThat(events("InvoiceCancelled")).hasSize(8);
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("REMITTANCE_ROLLED_BACK")), Document.class, "audit_entries"))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.getString("reason")).isEqualTo("Preu equivocat");
                    assertThat(entry.get("details", Document.class).getList("invoiceIds", String.class)).hasSize(8);
                });
        var view = ok(admin(get("/api/v1/billing/runs/" + runId)), 200);
        assertThat(view.path("status").asText()).isEqualTo("ROLLED_BACK");
        assertThat(view.path("rollbackable").asBoolean()).isFalse();
        error(admin(keyed(post("/api/v1/billing/runs/" + runId + "/rollback"), Map.of("reason", "Una altra vegada", "confirmation", "RETROCEDIR"))), 409, "RUN_NOT_ROLLBACKABLE");
        // Simulated and generated again, the same members get the same numbers.
        clock.setInstant(NOW.plusSeconds(300));
        var second = run("2026-09", simulate("2026-09").path("id").asText());
        assertThat(second.at("/run/id").asText()).isNotEqualTo(runId);
        var renumbered = new LinkedHashMap<String, String>();
        for (var invoice : invoices()) { if (!"CANCELLED".equals(invoice.getString("status"))) { renumbered.put(invoice.getString("memberId"), invoice.getString("displayNumber")); } }
        assertThat(renumbered).isEqualTo(numbers);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "remittances")).isEqualTo(2);
        assertThat(second.at("/remittance/messageId").asText()).isEqualTo("bill-a-2026-09-2");
    }
    private Map<String, String> numbersByMember() {
        var numbers = new LinkedHashMap<String, String>();
        for (var invoice : invoices()) { numbers.put(invoice.getString("memberId"), invoice.getString("displayNumber")); }
        return numbers;
    }

    @Test void T_12_13_aSubmittedRemittanceAPaidInvoiceOrALaterInvoiceBlockTheRollback() throws Exception {
        var result = run("2026-09", simulate("2026-09").path("id").asText());
        String runId = result.at("/run/id").asText();
        // A cash invoice already paid (R-12-16).
        var vila = invoices().stream().filter(invoice -> invoice.getString("memberId").equals("vila")).findFirst().orElseThrow();
        ok(admin(keyed(post("/api/v1/invoices/" + vila.getString("_id") + "/payment"), Map.of("paidAt", "2026-08-25", "channel", "CASH", "version", 0))), 200);
        rollbackRefused(runId, "INVOICE_PAID");
        // A manual invoice numbered after the run.
        ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "puig", "lines", List.of(Map.of("description", "Ajust", "base",
                Map.of("amountMinor", 500, "currency", "EUR"), "taxPercent", 0)), "note", "Ajust de prova"))), 201);
        rollbackRefused(runId, "INVOICE_PAID", "MANUAL_INVOICE_AFTER");
        // The remittance marked as sent to the bank (E8-T03 writes the submission; here the document is set directly).
        mongo.updateFirst(Query.query(Criteria.where("runId").is(runId)), new Update().set("status", "SUBMITTED"), "remittances");
        rollbackRefused(runId, "REMITTANCE_SUBMITTED", "INVOICE_PAID", "MANUAL_INVOICE_AFTER");
        var period = ok(admin(get("/api/v1/billing/periods/2026-09")), 200);
        assertThat(period.at("/run/rollbackable").asBoolean()).isFalse();
        assertThat(strings(period.at("/run/rollbackBlockers"))).containsExactly("REMITTANCE_SUBMITTED", "INVOICE_PAID", "MANUAL_INVOICE_AFTER");
        assertThat(invoices()).noneMatch(invoice -> "CANCELLED".equals(invoice.getString("status")));
    }
    private void rollbackRefused(String runId, String... reasons) throws Exception {
        var response = call(admin(keyed(post("/api/v1/billing/runs/" + runId + "/rollback"), Map.of("reason", "Error", "confirmation", "RETROCEDIR"))));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("RUN_NOT_ROLLBACKABLE");
        assertThat(strings(json(response).at("/details/reasons"))).containsExactly(reasons);
    }
    static List<String> strings(JsonNode node) { var values = new ArrayList<String>(); node.forEach(value -> values.add(value.asText())); return values; }

    @Test void T_12_23_aRunAt2330InBuenosAiresKeepsTheClubsLocalDay() throws Exception {
        club(CLUB, HOST, "America/Argentina/Buenos_Aires", List.of(Module.values()), providers(true, true, false));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().set("billing", new Document("nextNumber", 912L)), "clubs");
        configs.invalidate(CLUB);
        clock.setInstant(Instant.parse("2026-08-26T02:30:00Z")); // 25-08 23:30 in Buenos Aires, already 26-08 in UTC
        var result = run("2026-09", simulate("2026-09").path("id").asText());
        assertThat(invoices()).allSatisfy(invoice -> assertThat(invoice.getString("issueDate")).isEqualTo("2026-08-25"));
        assertThat(result.at("/run/collectionDate").asText()).isEqualTo("2026-09-01");
        // A payment dated «today» (the club's 25-08) is accepted, its instant is now; the club's tomorrow is refused.
        var vila = invoices().stream().filter(invoice -> invoice.getString("memberId").equals("vila")).findFirst().orElseThrow();
        error(admin(keyed(post("/api/v1/invoices/" + vila.getString("_id") + "/payment"), Map.of("paidAt", "2026-08-26", "channel", "CASH", "version", 0))), 400, "VALIDATION_ERROR");
        var paid = ok(admin(keyed(post("/api/v1/invoices/" + vila.getString("_id") + "/payment"), Map.of("paidAt", "2026-08-25", "channel", "CASH", "version", 0))), 200);
        assertThat(paid.path("paidAt").asText()).isEqualTo("2026-08-26T02:30:00Z");
    }

    @Test void T_12_29b_theCollectionDayParameterTheExplicitDateAndTheTwoBusinessDays() throws Exception {
        parameter(CLUB, "billing.sepa.collectionDayOfMonth", 0);
        clock.setInstant(NOW.plusSeconds(60));
        var lastDay = run("2026-09", simulate("2026-09").path("id").asText());
        assertThat(lastDay.at("/run/collectionDate").asText()).isEqualTo("2026-09-30");
        assertThat(lastDay.at("/remittance/requestedCollectionDate").asText()).isEqualTo("2026-09-30");
        // Changing the key afterwards never recomputes a generated remittance (R-02-04).
        parameter(CLUB, "billing.sepa.collectionDayOfMonth", 1);
        assertThat(mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "remittances").getString("requestedCollectionDate")).isEqualTo("2026-09-30");
        // 28 of February, from a run on 25-01-2027.
        clock.setInstant(Instant.parse("2027-01-25T09:00:00Z"));
        parameter(CLUB, "billing.sepa.collectionDayOfMonth", 28);
        mongo.updateMulti(Query.query(Criteria.where("clubId").is(CLUB).and("nextInvoiceDate").is("2026-10-01")), new Update().set("nextInvoiceDate", "2027-02-01"), "members");
        var february = run("2027-02", simulate("2027-02").path("id").asText());
        assertThat(february.at("/run/collectionDate").asText()).isEqualTo("2027-02-28");
        // An explicit date wins, and a date that leaves less than two business days is refused with the earliest one.
        clock.setInstant(Instant.parse("2027-02-26T09:00:00Z")); // Friday
        String march = simulate("2027-03").path("id").asText();
        var tooSoon = call(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2027-03", "simulationId", march, "collectionDate", "2027-03-01"))));
        assertThat(tooSoon.getStatus()).as(tooSoon.getContentAsString()).isEqualTo(422);
        assertThat(json(tooSoon).path("code").asText()).isEqualTo("COLLECTION_DATE_TOO_SOON");
        assertThat(json(tooSoon).at("/details/requested").asText()).isEqualTo("2027-03-01");
        assertThat(json(tooSoon).at("/details/earliest").asText()).isEqualTo("2027-03-02");
        var explicit = ok(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2027-03", "simulationId", march, "collectionDate", "2027-03-05"))), 201);
        assertThat(explicit.at("/run/collectionDate").asText()).isEqualTo("2027-03-05");
    }

    @Test void T_12_32_aManualOnlyClubGetsPendingInvoicesWithoutRemittanceAndTheTextsAreFrozenInTheClubsLanguage() throws Exception {
        club(CLUB, HOST, "Europe/Madrid", List.of(Module.values()), providers(false, true, false));
        mongo.updateMulti(Query.query(Criteria.where("clubId").is(CLUB).and("paymentMethod.type").is("SEPA_DD")), new Update().set("paymentMethod", new Document(cash())), "members");
        // The admin works in Spanish: the frozen descriptions stay in the club's Catalan (R-12-30).
        var simulation = ok(admin(post("/api/v1/billing/simulations").header("Accept-Language", "es").contentType("application/json").content("{\"period\":\"2026-09\"}")), 201);
        var result = ok(admin(keyed(post("/api/v1/billing/runs").header("Accept-Language", "es"), Map.of("period", "2026-09", "simulationId", simulation.path("id").asText()))), 201);
        assertThat(result.path("remittance").isNull()).isTrue();
        assertThat(result.at("/run/byProvider").has("SEPA_XML")).isFalse();
        assertThat(result.at("/run/collectionDate").isNull()).isTrue();
        assertThat(invoices()).isNotEmpty().allSatisfy(invoice -> assertThat(invoice.getString("status")).isEqualTo("PENDING"));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "remittances")).isZero();
        assertThat(invoices().stream().flatMap(invoice -> invoice.getList("lines", Document.class).stream()).map(line -> line.getString("description")))
                .contains("Quota Abonat — Setembre 2026").noneMatch(text -> text.startsWith("Cuota"));
    }

    @Test void T_12_22_withoutBillingTheCycleIsNotThereAndNothingIsWritten() throws Exception {
        modules(CLUB, List.of(Module.FAQ));
        error(admin(post("/api/v1/billing/simulations").contentType("application/json").content("{\"period\":\"2026-09\"}")), 404, "MODULE_DISABLED");
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", "x"))), 404, "MODULE_DISABLED");
        error(admin(get("/api/v1/billing/periods/2026-09")), 404, "MODULE_DISABLED");
        error(admin(get("/api/v1/invoices")), 404, "MODULE_DISABLED");
        error(as(get("/api/v1/me/invoices"), CLUB, "MEMBER", "puig"), 404, "MODULE_DISABLED");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "billing_simulations")).isZero();
        assertThat(member("puig", "nextInvoiceDate")).isEqualTo("2026-09-01");
    }
}

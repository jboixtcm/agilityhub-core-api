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
        // E8-T03: the pain.008 file is written with the run (E8-T02's stub had none).
        assertThat(period.at("/remittance/fileAvailable").asBoolean()).isTrue();
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

    /** Round 2 (review #7, ruling E87): the club's own configuration — payment providers, modules — counts as a relevant change. */
    @Test void T_12_09_R_12_07_aChangeOfTheClubsProvidersOrModulesMakesTheSimulationStale() throws Exception {
        admin("bill-admin-1", CLUB);
        // A module toggled through D11 (PUT /club/modules, ClubSettingsService stamps the club's updatedAt).
        String first = simulate("2026-09").path("id").asText();
        clock.setInstant(NOW.plusSeconds(60));
        ok(put("/api/v1/club/modules/FAQ").header("Host", HOST).contentType("application/json").content("{\"enabled\":false}").with(jwtAccount("bill-admin-1")), 200);
        assertThat(mongo.findById(CLUB, Document.class, "clubs").getDate("updatedAt").toInstant()).isEqualTo(clock.instant());
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", first))), 409, "SIMULATION_STALE");
        // SEPA_XML disabled as `club:apply` writes it (the provider's `enabled` and the club's `updatedAt`, S17 R-17-05).
        String second = simulate("2026-09").path("id").asText();
        clock.setInstant(NOW.plusSeconds(120));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().set("paymentProviders.SEPA_XML.enabled", false)
                .set("updatedAt", java.util.Date.from(clock.instant())), "clubs");
        configs.invalidate(CLUB);
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", second))), 409, "SIMULATION_STALE");
        assertThat(invoices()).isEmpty();
        // Simulated again, the run is what the confirmation showed: the SEPA members are skipped as PROVIDER_DISABLED. A receipt
        // numbered meanwhile only moves the club's counter (its version), never the simulation's freshness.
        var third = simulate("2026-09");
        assertThat(third.path("incidents").findValuesAsText("code")).contains("PROVIDER_DISABLED");
        ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "vila", "lines", List.of(Map.of("description", "Ajust", "base",
                Map.of("amountMinor", 500, "currency", "EUR"), "taxPercent", 0)), "note", "Ajust"))), 201);
        var result = run("2026-09", third.path("id").asText());
        assertThat(result.path("skipped").findValuesAsText("code")).containsExactlyElementsOf(third.path("incidents").findValuesAsText("code"));
        assertThat(result.path("remittance").isNull()).isTrue();
    }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtAccount(String accountId) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(accountId).claim("clubId", CLUB))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
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
    /** Round 2 (review #10): a COMPLETED run (E8-T04 writes it once the cards are charged) is never rolled back (§5), with its reason. */
    @Test void T_12_13_aCompletedRunAnswersRunNotRollbackableWithItsReason() throws Exception {
        String runId = run("2026-09", simulate("2026-09").path("id").asText()).at("/run/id").asText();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(runId)), new Update().set("status", "COMPLETED"), "billing_runs");
        rollbackRefused(runId, "COLLECTION_SUBMITTED");
        var period = ok(admin(get("/api/v1/billing/periods/2026-09")), 200);
        assertThat(period.at("/run/rollbackable").asBoolean()).isFalse();
        assertThat(strings(period.at("/run/rollbackBlockers"))).containsExactly("COLLECTION_SUBMITTED");
        assertThat(invoices()).noneMatch(invoice -> "CANCELLED".equals(invoice.getString("status")));
    }

    /**
     * Round 2 (review #5, ruling E87): after a rollback and a new generation a member's number exists twice — the rolled-back
     * receipt and the reissued one. The rolled-back one never reaches the member, D10, D6's «Tots» or the search; D6 shows it
     * only under the CANCELLED filter, marked as rolled back. An admin's own cancellation stays everywhere.
     */
    @Test void T_12_13_R_12_27_aReceiptCancelledByARollbackOnlyShowsUnderTheCancelledFilter() throws Exception {
        String runId = run("2026-09", simulate("2026-09").path("id").asText()).at("/run/id").asText();
        String rolledBack = invoices().stream().filter(invoice -> invoice.getString("memberId").equals("puig")).findFirst().orElseThrow().getString("_id");
        ok(admin(keyed(post("/api/v1/billing/runs/" + runId + "/rollback"), Map.of("reason", "Preu equivocat", "confirmation", "RETROCEDIR"))), 200);
        clock.setInstant(NOW.plusSeconds(300));
        run("2026-09", simulate("2026-09").path("id").asText());
        var live = invoices().stream().filter(invoice -> invoice.getString("memberId").equals("puig") && !"CANCELLED".equals(invoice.getString("status")))
                .findFirst().orElseThrow();
        assertThat(live.getString("displayNumber")).isEqualTo(mongo.findById(rolledBack, Document.class, "invoices").getString("displayNumber"));
        // An admin's cancellation of a reissued cash receipt is a real one: it stays in «Tots».
        String vila = invoices().stream().filter(invoice -> invoice.getString("memberId").equals("vila") && "PENDING".equals(invoice.getString("status")))
                .findFirst().orElseThrow().getString("_id");
        ok(admin(keyed(post("/api/v1/invoices/" + vila + "/cancellation"), Map.of("reason", "Duplicat", "version", 0))), 200);
        // D6: «Tots (n)» and the list count the 8 live receipts (the admin's cancellation included), not the 16 documents.
        var period = ok(admin(get("/api/v1/billing/periods/2026-09")), 200);
        assertThat(period.at("/counts/all").asLong()).isEqualTo(8);
        assertThat(ok(admin(get("/api/v1/invoices")), 200).path("totalItems").asLong()).isEqualTo(8);
        assertThat(rowIds(ok(admin(get("/api/v1/invoices").param("q", live.getString("displayNumber").substring(5))), 200))).containsExactly(live.getString("_id"));
        assertThat(rowIds(ok(admin(get("/api/v1/invoices").param("filter", "memberId:eq:puig")), 200))).containsExactly(live.getString("_id"));
        // The CANCELLED chip: the 8 rolled-back receipts marked rolledBack, and the admin's one not.
        var cancelled = ok(admin(get("/api/v1/invoices").param("filter", "status:eq:CANCELLED").param("size", "20")), 200);
        assertThat(cancelled.path("totalItems").asLong()).isEqualTo(9);
        var marks = new LinkedHashMap<String, Boolean>();
        cancelled.path("items").forEach(item -> marks.put(item.path("id").asText(), item.path("rolledBack").asBoolean()));
        assertThat(marks).containsEntry(rolledBack, true).containsEntry(vila, false);
        assertThat(marks.values().stream().filter(Boolean::booleanValue)).hasSize(8);
        assertThat(ok(admin(get("/api/v1/invoices").param("filter", "status:in:CANCELLED,PAID")), 200).path("totalItems").asLong()).isEqualTo(9);
        assertThat(ok(admin(get("/api/v1/invoices").param("fields", "rolledBack")), 200).path("items").findValuesAsText("rolledBack")).containsOnly("false");
        // The member: only the reissued receipt; the rolled-back one and its PDF are 404. D10 shows the live one only.
        var mine = ok(as(get("/api/v1/me/invoices"), CLUB, "MEMBER", "puig"), 200);
        assertThat(rowIds(mine)).containsExactly(live.getString("_id"));
        assertThat(mine.path("totalItems").asLong()).isEqualTo(1);
        error(as(get("/api/v1/me/invoices/" + rolledBack), CLUB, "MEMBER", "puig"), 404, "NOT_FOUND");
        error(as(get("/api/v1/me/invoices/" + rolledBack + "/document"), CLUB, "MEMBER", "puig"), 404, "NOT_FOUND");
        var overview = ok(admin(get("/api/v1/members/puig/overview")), 200);
        assertThat(overview.path("invoicesCount").asInt()).isEqualTo(1);
        assertThat(overview.at("/recentInvoices/0/id").asText()).isEqualTo(live.getString("_id"));
        // The admin still reads it, as it is: CANCELLED{ROLLBACK}, rolled back by its run (E8-T07 step 4).
        assertThat(ok(admin(get("/api/v1/invoices/" + rolledBack)), 200).path("cancelReason").asText()).isEqualTo("ROLLBACK");
    }

    /**
     * E8-T07 step 1 (review #1 of E8-T02's round 2, ruling E89; R-12-12, R-12-16, R-12-19): a manual receipt whose total is zero
     * or negative is never direct-debited. With `includeInNextRun` it is refused at creation; one stored with the flag before
     * the fix is left out of the simulation and of the run; the admin settles it by hand. R-12-19's own example.
     */
    @Test void T_12_18_R_12_19_aReceiptWithAZeroOrNegativeTotalIsNeverDirectDebitedAndTheAdminSettlesItByHand() throws Exception {
        var minus = Map.of("description", "Ajust quota setembre", "base", Map.of("amountMinor", -3000, "currency", "EUR"), "taxPercent", 0);
        var plus = Map.of("description", "Quota setembre", "base", Map.of("amountMinor", 3000, "currency", "EUR"), "taxPercent", 0);
        var refused = call(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "puig", "lines", List.of(minus), "includeInNextRun", true, "note", "Ajust"))));
        assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(400);
        assertThat(json(refused).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(json(refused).at("/details/field").asText()).isEqualTo("includeInNextRun");
        // A zero total too (two lines that cancel out); nothing is stored and no number is taken.
        error(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "puig", "lines", List.of(plus, minus), "includeInNextRun", true, "note", "Ajust"))), 400,
                "VALIDATION_ERROR");
        assertThat(invoices()).isEmpty();
        // Without the flag it is an ordinary adjustment (T-12-18): numbered, PENDING.
        var created = ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "puig", "lines", List.of(minus), "note", "Ajust quota setembre −30,00 €"))), 201);
        String id = created.path("id").asText();
        assertThat(created.path("displayNumber").asText()).isEqualTo("2026-0912");
        assertThat(created.at("/total/amountMinor").asLong()).isEqualTo(-3000);
        assertThat(created.path("includeInNextRun").asBoolean()).isFalse();
        // A receipt stored with the flag before the fix: neither the simulation nor the run debits it, whatever its flag says.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("includeInNextRun", true), "invoices");
        var simulation = simulate("2026-09");
        assertThat(simulation.path("invoicesPreview").findValuesAsText("memberId")).containsExactlyElementsOf(ORDER);
        assertThat(simulation.at("/kpis/byProvider/SEPA_XML/count").asInt()).isEqualTo(4);
        assertThat(simulation.at("/kpis/byProvider/SEPA_XML/total/amountMinor").asLong()).isEqualTo(20_400);
        var result = run("2026-09", simulation.path("id").asText());
        assertThat(result.at("/remittance/count").asInt()).isEqualTo(4);
        assertThat(result.at("/remittance/total/amountMinor").asLong()).isEqualTo(20_400);
        var stored = mongo.findById(id, Document.class, "invoices");
        assertThat(stored.getString("status")).isEqualTo("PENDING");
        assertThat(stored.get("remittanceId")).isNull();
        assertThat(attempts(id)).isEmpty();
        // R-12-16: the admin settles it by hand (the 30 € handed back by transfer).
        var paid = ok(admin(keyed(post("/api/v1/invoices/" + id + "/payment"), Map.of("paidAt", "2026-08-25", "channel", "TRANSFER",
                "version", ((Number) stored.get("version")).longValue()))), 200);
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        assertThat(attempts(id)).containsExactly("MANUAL SUCCEEDED 1 null");
    }

    /**
     * E8-T07 step 2 (review #2, ruling E89; T-12-13, R-12-14): a rollback rolls back its whole block, a receipt the admin
     * cancelled meanwhile included (its admin reason kept), and the next generation reissues the same numbers: no gap, no
     * duplicate among the live receipts. The review's 0912…1026 with 0950 cancelled is this census's 0912…0919 with Vila's 0917.
     */
    @Test @AuditCovers(AuditAction.REMITTANCE_ROLLED_BACK)
    void T_12_13_R_12_14_aRollbackReissuesItsWholeBlockAlsoAfterTheAdminCancelledOneOfItsReceipts() throws Exception {
        String runId = run("2026-09", simulate("2026-09").path("id").asText()).at("/run/id").asText();
        var numbers = numbersByMember();
        assertThat(numbers.values()).containsExactly("2026-0912", "2026-0913", "2026-0914", "2026-0915", "2026-0916", "2026-0917", "2026-0918", "2026-0919");
        String vila = invoices().stream().filter(invoice -> invoice.getString("memberId").equals("vila")).findFirst().orElseThrow().getString("_id");
        assertThat(numbers).containsEntry("vila", "2026-0917");
        clock.setInstant(NOW.plusSeconds(60));
        ok(admin(keyed(post("/api/v1/invoices/" + vila + "/cancellation"), Map.of("reason", "Duplicat", "version", 0))), 200);
        // Nothing blocks the rollback, and it rolls back the 8 receipts of the run: the admin's cancellation too.
        clock.setInstant(NOW.plusSeconds(120));
        var rollback = ok(admin(keyed(post("/api/v1/billing/runs/" + runId + "/rollback"), Map.of("reason", "Preu equivocat", "confirmation", "RETROCEDIR"))), 200);
        assertThat(rollback.path("cancelledInvoices").asInt()).isEqualTo(8);
        // The next generation starts again at 0912: the same numbers, no gap, no duplicate among the live receipts.
        clock.setInstant(NOW.plusSeconds(300));
        run("2026-09", simulate("2026-09").path("id").asText());
        var live = new LinkedHashMap<String, String>();
        for (var invoice : invoices()) { if (!"CANCELLED".equals(invoice.getString("status"))) { live.put(invoice.getString("memberId"), invoice.getString("displayNumber")); } }
        assertThat(live).isEqualTo(numbers);
        assertThat(mongo.findById(CLUB, Document.class, "clubs").get("billing", Document.class).get("counters", Document.class)).containsEntry("2026", 920L);
        assertThat(ok(admin(get("/api/v1/invoices")), 200).path("totalItems").asLong()).isEqualTo(8);
        // Vila's first receipt is rolled back with the others: its admin reason and date kept, its attempt closed by FAILED{ROLLBACK}.
        var cancelled = mongo.findById(vila, Document.class, "invoices");
        assertThat(cancelled.getString("status")).isEqualTo("CANCELLED");
        assertThat(cancelled.getString("cancelReason")).isEqualTo("Duplicat");
        assertThat(cancelled.getDate("cancelledAt").toInstant()).isEqualTo(NOW.plusSeconds(60));
        assertThat(attempts(vila)).containsExactly("MANUAL CREATED 1 null", "MANUAL FAILED 1 null");
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("status").is("FAILED").and("failureCode").is("ROLLBACK")), Document.class, "collections"))
                .hasSize(8).allSatisfy(collection -> assertThat(collection.getString("invoiceId")).isIn(runInvoiceIds(runId)));
        assertThat(events("InvoiceCancelled")).filteredOn(event -> "ROLLBACK".equals(event.get("payload", Document.class).getString("reason"))).hasSize(8);
        var rows = ok(admin(get("/api/v1/invoices").param("filter", "status:eq:CANCELLED").param("size", "20")), 200);
        assertThat(rows.path("totalItems").asLong()).isEqualTo(8);
        assertThat(rows.path("items").findValuesAsText("rolledBack")).hasSize(8).containsOnly("true");
        assertThat(ok(admin(get("/api/v1/invoices/" + vila)), 200).path("rolledBack").asBoolean()).isTrue();
    }
    /** The ids of {@code runId}'s receipts. */
    private List<String> runInvoiceIds(String runId) {
        return invoices().stream().filter(invoice -> runId.equals(invoice.getString("runId"))).map(invoice -> invoice.getString("_id")).toList();
    }

    /**
     * E8-T07 step 3 (review #3, ruling E89; R-12-07, R-12-19): the waiting manual receipts the run would remit are part of the
     * simulation — in `invoicesPreview` (with their `invoiceId` and number) and in the KPIs — so D6's confirmation and «Import
     * de la remesa» are the remittance's.
     */
    @Test void T_12_09_R_12_07_R_12_19_theWaitingManualReceiptsAreInThePreviewAndInTheRemittanceKpi() throws Exception {
        String included = flagged("puig", 2500);
        var simulation = simulate("2026-09");
        var receipt = previewOf(simulation, included);
        assertThat(receipt.path("memberId").asText()).isEqualTo("puig");
        assertThat(receipt.path("displayNumber").asText()).isEqualTo("2026-0912");
        assertThat(receipt.path("paymentMethodType").asText()).isEqualTo("SEPA_DD");
        assertThat(receipt.at("/total/amountMinor").asLong()).isEqualTo(2500);
        assertThat(receipt.at("/lines/0/origin").asText()).isEqualTo("ADJUSTMENT");
        // The run's own invoices do not exist yet: no invoiceId, no number (the keys are absent, as in S12 §6's JSON).
        assertThat(preview(simulation, "mas").has("invoiceId")).isFalse();
        assertThat(preview(simulation, "mas").has("displayNumber")).isFalse();
        var kpis = simulation.path("kpis");
        assertThat(kpis.path("count").asInt()).isEqualTo(9);
        assertThat(kpis.at("/total/amountMinor").asLong()).isEqualTo(116_400 + 2_500);
        assertThat(kpis.at("/byProvider/SEPA_XML/count").asInt()).isEqualTo(5);
        assertThat(kpis.at("/byProvider/SEPA_XML/total/amountMinor").asLong()).isEqualTo(22_900);
        assertThat(events("RemittanceSimulated")).singleElement()
                .satisfies(event -> assertThat(event.get("payload", Document.class).get("totals", Document.class).getInteger("count")).isEqualTo(9));
        var result = run("2026-09", simulation.path("id").asText());
        assertThat(result.at("/remittance/count").asInt()).isEqualTo(kpis.at("/byProvider/SEPA_XML/count").asInt());
        assertThat(result.at("/remittance/total")).isEqualTo(kpis.at("/byProvider/SEPA_XML/total"));
        assertThat(result.at("/run/byProvider/SEPA_XML/total")).isEqualTo(kpis.at("/byProvider/SEPA_XML/total"));
        assertThat(mongo.findById(included, Document.class, "invoices").getString("status")).isEqualTo("COLLECTING");
    }

    /**
     * E8-T07 step 3 (R-12-07, R-12-19): a waiting receipt gets the run's incident checks. Its member has left, has no account
     * left, no longer pays by SEPA_DD or signed another mandate since the receipt (E8-T03's review #1): an incident in the
     * simulation and in `skipped[]`, and the receipt is not remitted (it keeps its flag for the next run). The others still go.
     */
    @Test void T_12_09_R_12_07_R_12_19_aWaitingReceiptWhoseMemberCannotBeDebitedIsAnIncidentAndStaysOut() throws Exception {
        String remitted = flagged("puig", 2500), left = flagged("roca", 1000), noAccount = flagged("torres", 1100), cash = flagged("serra-joan", 1200),
                mandate = flagged("mas", 1300);
        clock.setInstant(NOW.plusSeconds(60));
        census("roca", new Update().set("status", "LEFT"));
        census("torres", new Update().unset("paymentMethod.iban"));
        census("serra-joan", new Update().set("paymentMethod", new Document(cash())));
        census("mas", new Update().set("paymentMethod.mandateRef", CLUB + "-207-2"));
        clock.setInstant(NOW.plusSeconds(120));
        var simulation = simulate("2026-09");
        assertThat(incidents(simulation.path("incidents"))).containsEntry("roca", "NO_BANK_ACCOUNT").containsEntry("torres", "NO_BANK_ACCOUNT")
                .containsEntry("serra-joan", "NO_BANK_ACCOUNT").containsEntry("mas", "NO_BANK_ACCOUNT");
        assertThat(simulation.path("incidents").findValuesAsText("memberId")).doesNotHaveDuplicates();
        assertThat(receiptIds(simulation)).containsExactly(remitted);
        // The run: the same incidents skipped, only Eva's receipt in the remittance, which is what the simulation announced.
        var result = run("2026-09", simulation.path("id").asText());
        assertThat(incidents(result.path("skipped"))).isEqualTo(incidents(simulation.path("incidents")));
        assertThat(result.at("/remittance/count").asInt()).isEqualTo(simulation.at("/kpis/byProvider/SEPA_XML/count").asInt());
        assertThat(result.at("/remittance/total")).isEqualTo(simulation.at("/kpis/byProvider/SEPA_XML/total"));
        assertThat(mongo.findById(remitted, Document.class, "invoices").getString("status")).isEqualTo("COLLECTING");
        for (String waiting : List.of(left, noAccount, cash, mandate)) {
            var stored = mongo.findById(waiting, Document.class, "invoices");
            assertThat(stored.getString("status")).as(waiting).isEqualTo("PENDING");
            assertThat(stored.get("remittanceId")).as(waiting).isNull();
            assertThat(stored.getBoolean("includeInNextRun")).as(waiting).isTrue();
            assertThat(attempts(waiting)).as(waiting).isEmpty();
        }
    }

    /** E8-T07 step 3 (R-12-07): a flagged receipt created after the simulation makes it stale; simulated again, it is in. */
    @Test void T_12_09_R_12_07_aFlaggedReceiptCreatedAfterTheSimulationMakesItStale() throws Exception {
        String simulation = simulate("2026-09").path("id").asText();
        clock.setInstant(NOW.plusSeconds(60));
        String receipt = flagged("puig", 2500);
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", simulation))), 409, "SIMULATION_STALE");
        assertThat(invoices()).extracting(invoice -> invoice.getString("_id")).containsExactly(receipt);
        var fresh = simulate("2026-09");
        assertThat(receiptIds(fresh)).containsExactly(receipt);
        run("2026-09", fresh.path("id").asText());
        assertThat(mongo.findById(receipt, Document.class, "invoices").getString("status")).isEqualTo("COLLECTING");
    }

    /** E8-T07 step 3 (R-12-07, R-12-25): a pending charge created — or voided — after the simulation makes it stale. */
    @Test void T_12_09_R_12_07_R_12_25_aPendingChargeCreatedAfterTheSimulationMakesItStale() throws Exception {
        String simulation = simulate("2026-09").path("id").asText();
        clock.setInstant(NOW.plusSeconds(60));
        mongo.save(new Document("_id", "bill-charge-3").append("clubId", CLUB).append("memberId", "mas").append("dogId", "bill-dog-mas")
                .append("bookingId", "bill-booking-3").append("priceId", "bill-price-single-single_class")
                .append("amount", new Document("amountMinor", 1200L).append("currency", "EUR")).append("description", "Classe 18/08 — Duna")
                .append("createdAt", java.util.Date.from(clock.instant())), "pending_charges");
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", simulation))), 409, "SIMULATION_STALE");
        var fresh = simulate("2026-09");
        assertThat(preview(fresh, "mas").path("lines").findValuesAsText("description")).contains("Classe 18/08 — Duna");
        clock.setInstant(NOW.plusSeconds(120));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("bill-charge-3")), new Update().set("voidedAt", java.util.Date.from(clock.instant())), "pending_charges");
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", fresh.path("id").asText()))), 409, "SIMULATION_STALE");
        assertThat(invoices()).isEmpty();
        run("2026-09", simulate("2026-09").path("id").asText());
        assertThat(invoices().getFirst().getList("lines", Document.class)).extracting(line -> line.getString("description"))
                .containsExactly("Classe 04/08 — Duna", "Classe 11/08 — Duna");
    }

    /**
     * E8-T07 step 4 (review #4, ruling E89; §5): a receipt is rolled back because its run is `ROLLED_BACK`, never because of
     * the words of its `cancelReason`: the admin's reason is free text again, «ROLLBACK» included.
     */
    @Test void T_12_19_R_12_14_rolledBackComesFromTheRunAndTheAdminsReasonIsFreeText() throws Exception {
        String runId = run("2026-09", simulate("2026-09").path("id").asText()).at("/run/id").asText();
        String vives = invoices().stream().filter(invoice -> invoice.getString("memberId").equals("vives")).findFirst().orElseThrow().getString("_id");
        var cancelled = ok(admin(keyed(post("/api/v1/invoices/" + vives + "/cancellation"), Map.of("reason", "ROLLBACK", "version", 0))), 200);
        assertThat(cancelled.path("cancelReason").asText()).isEqualTo("ROLLBACK");
        assertThat(cancelled.path("rolledBack").asBoolean()).isFalse();
        // Its run is live: an admin's cancellation, everywhere — «Tots», the CANCELLED chip unmarked, the member's receipts, D10.
        assertThat(ok(admin(get("/api/v1/invoices")), 200).path("totalItems").asLong()).isEqualTo(8);
        assertThat(ok(admin(get("/api/v1/billing/periods/2026-09")), 200).at("/counts/all").asLong()).isEqualTo(8);
        var chip = ok(admin(get("/api/v1/invoices").param("filter", "status:eq:CANCELLED")), 200);
        assertThat(rowIds(chip)).containsExactly(vives);
        assertThat(chip.at("/items/0/rolledBack").asBoolean()).isFalse();
        assertThat(rowIds(ok(as(get("/api/v1/me/invoices"), CLUB, "MEMBER", "vives"), 200))).containsExactly(vives);
        ok(as(get("/api/v1/me/invoices/" + vives), CLUB, "MEMBER", "vives"), 200);
        assertThat(ok(admin(get("/api/v1/members/vives/overview")), 200).path("invoicesCount").asInt()).isEqualTo(1);
        // Once its run is rolled back the receipt is too, its reason unchanged.
        clock.setInstant(NOW.plusSeconds(60));
        ok(admin(keyed(post("/api/v1/billing/runs/" + runId + "/rollback"), Map.of("reason", "Preu equivocat", "confirmation", "RETROCEDIR"))), 200);
        var detail = ok(admin(get("/api/v1/invoices/" + vives)), 200);
        assertThat(detail.path("rolledBack").asBoolean()).isTrue();
        assertThat(detail.path("cancelReason").asText()).isEqualTo("ROLLBACK");
        assertThat(ok(admin(get("/api/v1/invoices")), 200).path("totalItems").asLong()).isZero();
        assertThat(ok(as(get("/api/v1/me/invoices"), CLUB, "MEMBER", "vives"), 200).path("totalItems").asLong()).isZero();
        error(as(get("/api/v1/me/invoices/" + vives), CLUB, "MEMBER", "vives"), 404, "NOT_FOUND");
        assertThat(ok(admin(get("/api/v1/members/vives/overview")), 200).path("invoicesCount").asInt()).isZero();
    }

    /** A manual `SEPA_DD` receipt of {@code cents} with `includeInNextRun` (R-12-19); its id. */
    private String flagged(String memberId, long cents) throws Exception {
        var created = ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", memberId, "includeInNextRun", true, "note", "Amb la remesa",
                "lines", List.of(Map.of("description", "Quota pendent", "base", Map.of("amountMinor", cents, "currency", "EUR"), "taxPercent", 0))))), 201);
        assertThat(created.path("includeInNextRun").asBoolean()).as(memberId).isTrue();
        return created.path("id").asText();
    }
    private void census(String memberId, Update update) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(memberId)), update.set("updatedAt", java.util.Date.from(clock.instant())), "members");
    }
    private static JsonNode previewOf(JsonNode simulation, String invoiceId) {
        for (var preview : simulation.path("invoicesPreview")) { if (preview.path("invoiceId").asText().equals(invoiceId)) { return preview; } }
        throw new AssertionError(invoiceId + " not previewed");
    }
    /** The waiting receipts a simulation previews (the run's own invoices have no id yet). */
    private static List<String> receiptIds(JsonNode simulation) {
        var ids = new ArrayList<String>();
        simulation.path("invoicesPreview").forEach(preview -> { if (preview.path("invoiceId").isTextual()) { ids.add(preview.path("invoiceId").asText()); } });
        return ids;
    }
    /** memberId → code of a simulation's incidents or a run's skipped[]. */
    private static Map<String, String> incidents(JsonNode incidents) {
        var codes = new LinkedHashMap<String, String>();
        incidents.forEach(incident -> codes.put(incident.path("memberId").asText(), incident.path("code").asText()));
        return codes;
    }

    /**
     * Round 2 (review #4, ruling E87): R-12-19's «inclòs a la propera remesa». The club's next run puts the PENDING manual
     * SEPA_DD receipts with includeInNextRun into its remittance; a rollback returns them to PENDING (not cancelled: the run
     * did not issue them), the flag kept, and the next run takes them again.
     */
    @Test @AuditCovers(AuditAction.REMITTANCE_GENERATED)
    void T_12_18_R_12_19_aManualSepaReceiptWithIncludeInNextRunJoinsTheNextRemittanceAndARollbackReturnsIt() throws Exception {
        var adjustment = Map.of("description", "Quota d'agost pendent", "base", Map.of("amountMinor", 2500, "currency", "EUR"), "taxPercent", 0);
        String included = ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "puig", "lines", List.of(adjustment), "includeInNextRun", true,
                "note", "Cobrar amb la remesa"))), 201).path("id").asText();
        // A cash member's flag is not kept (no remittance can take it); a SEPA receipt without the flag waits for R-12-16.
        String cash = ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "vila", "lines", List.of(adjustment), "includeInNextRun", true, "note", "x"))), 201)
                .path("id").asText();
        String unflagged = ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "torres", "lines", List.of(adjustment), "note", "x"))), 201).path("id").asText();
        var result = run("2026-09", simulate("2026-09").path("id").asText());
        String runId = result.at("/run/id").asText(), remittanceId = result.at("/remittance/id").asText();
        var receipt = mongo.findById(included, Document.class, "invoices");
        assertThat(receipt.getString("status")).isEqualTo("COLLECTING");
        assertThat(receipt.getString("remittanceId")).isEqualTo(remittanceId);
        assertThat(receipt.get("runId")).isNull();
        assertThat(attempts(included)).containsExactly("SEPA_XML CREATED 1 " + remittanceId);
        for (String waiting : List.of(cash, unflagged)) {
            assertThat(mongo.findById(waiting, Document.class, "invoices").getString("status")).isEqualTo("PENDING");
            assertThat(attempts(waiting)).isEmpty();
        }
        // The remittance carries the run's 4 SEPA receipts and this one (2500): 5 collections, 20400 + 2500.
        assertThat(result.at("/remittance/count").asInt()).isEqualTo(5);
        assertThat(result.at("/remittance/total/amountMinor").asLong()).isEqualTo(22_900);
        assertThat(result.at("/run/byProvider/SEPA_XML/count").asInt()).isEqualTo(5);
        assertThat(result.at("/run/invoiceIds")).hasSize(8);
        assertThat(events("InvoiceCollecting")).extracting(event -> event.getString("aggregateId")).contains(included);
        assertThat(events("RemittanceGenerated")).singleElement()
                .satisfies(event -> assertThat(event.get("payload", Document.class).getList("invoiceIds", String.class)).hasSize(5).contains(included));
        assertThat(mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("REMITTANCE_GENERATED")), Document.class, "audit_entries")
                .get("details", Document.class).getList("remittedManualInvoiceIds", String.class)).containsExactly(included);
        // The rollback: the run's own 8 cancelled, the manual receipt back to PENDING, out of the remittance, the flag kept.
        clock.setInstant(NOW.plusSeconds(60));
        var rollback = ok(admin(keyed(post("/api/v1/billing/runs/" + runId + "/rollback"), Map.of("reason", "Preu equivocat", "confirmation", "RETROCEDIR"))), 200);
        assertThat(rollback.path("cancelledInvoices").asInt()).isEqualTo(8);
        receipt = mongo.findById(included, Document.class, "invoices");
        assertThat(receipt.getString("status")).isEqualTo("PENDING");
        assertThat(receipt.get("remittanceId")).isNull();
        assertThat(receipt.getBoolean("includeInNextRun")).isTrue();
        assertThat(attempts(included)).containsExactly("SEPA_XML CREATED 1 " + remittanceId, "SEPA_XML FAILED 1 " + remittanceId);
        assertThat(events("RemittanceRolledBack")).singleElement()
                .satisfies(event -> assertThat(event.get("payload", Document.class).getList("invoiceIds", String.class)).hasSize(9).contains(included));
        // The next run takes it again, as attempt 2 of its new remittance.
        clock.setInstant(NOW.plusSeconds(300));
        var again = run("2026-09", simulate("2026-09").path("id").asText());
        receipt = mongo.findById(included, Document.class, "invoices");
        assertThat(receipt.getString("status")).isEqualTo("COLLECTING");
        assertThat(receipt.getString("remittanceId")).isEqualTo(again.at("/remittance/id").asText());
        assertThat(attempts(included)).last().isEqualTo("SEPA_XML CREATED 2 " + again.at("/remittance/id").asText());
    }
    /** The ids of a page's rows (a row's member has an `id` too). */
    private static List<String> rowIds(JsonNode page) { var ids = new ArrayList<String>(); page.path("items").forEach(item -> ids.add(item.path("id").asText())); return ids; }
    private List<String> attempts(String invoiceId) {
        return mongo.find(Query.query(Criteria.where("invoiceId").is(invoiceId)).with(org.springframework.data.domain.Sort.by("attempt", "createdAt")), Document.class, "collections")
                .stream().map(c -> c.getString("provider") + " " + c.getString("status") + " " + c.getInteger("attempt") + " " + c.getString("remittanceId")).toList();
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

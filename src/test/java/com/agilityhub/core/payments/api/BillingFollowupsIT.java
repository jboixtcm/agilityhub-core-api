package com.agilityhub.core.payments.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** E90 / E8-T08: current mandates, receipt incidents, exact plans and simulation dates on real Mongo/HTTP. */
class BillingFollowupsIT extends BillingItSupport {
    JsonNode waiting(String member) throws Exception {
        return ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", member, "includeInNextRun", true, "note", "Example",
                "lines", List.of(Map.of("description", "Quota d’agost – pendent", "base", Map.of("amountMinor", 2500, "currency", "EUR"), "taxPercent", 0))))), 201);
    }
    void change(String member, Update update) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(member)), update, "members");
    }
    @Test void R_12_07_R_12_10_waitingReceiptUsesTheCurrentMandateAndSignature() throws Exception {
        var receipt = waiting("puig");
        clock.setInstant(NOW.plusSeconds(60));
        ok(admin(patch("/api/v1/members/puig/payment-method").contentType("application/json").content(mapper.writeValueAsString(
                Map.of("type", "SEPA_DD", "sepa", Map.of("iban", RemittancesIT.iban("00000000000000900208"), "holderName", "New Holder Example"))))), 200);
        var simulation = simulate("2026-09");
        assertThat(simulation.path("incidents").findValuesAsText("memberId")).doesNotContain("puig");
        var result = run("2026-09", simulation.path("id").asText());
        var attempt = mongo.findOne(Query.query(Criteria.where("invoiceId").is(receipt.path("id").asText())), Document.class, "collections");
        assertThat(attempt).isNotNull();
        assertThat(attempt.getString("mandateRef")).isEqualTo("bill-a-208-2");
        assertThat(attempt.getDate("mandateSignedAt").toInstant()).isEqualTo(NOW.plusSeconds(60));
        var stored = mongo.findById(receipt.path("id").asText(), Document.class, "invoices");
        assertThat(stored.get("paymentMethod", Document.class).getString("mandateRef")).isEqualTo("bill-a-208-1");
        String key = mongo.findById(result.at("/remittance/id").asText(), Document.class, "remittances").getString("fileKey");
        String xml = Files.readString(Path.of("target/test-exports").resolve(key));
        String debit = Arrays.stream(xml.split("<DrctDbtTxInf>")).filter(part -> part.contains("<EndToEndId>" + receipt.path("displayNumber").asText() + "</EndToEndId>"))
                .findFirst().orElseThrow().split("</DrctDbtTxInf>")[0];
        assertThat(debit).contains("<MndtId>bill-a-208-2</MndtId>", "<DtOfSgntr>2026-08-25</DtOfSgntr>", "<Nm>New Holder Example</Nm>");
    }
    @Test void R_12_07_receiptIncidentsNameEveryReceiptAndNeverSkipABilledMember() throws Exception {
        var left = waiting("roca"); var pending = waiting("puig"); var cash = waiting("serra-joan"); var cashAgain = waiting("serra-joan");
        change("roca", new Update().set("status", "LEFT"));
        change("puig", new Update().set("status", "PENDING"));
        change("serra-joan", new Update().set("paymentMethod", new Document(cash())));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("bill-family")), new Update().pull("memberIds", "serra-joan"), "family_groups");
        change("serra-joan", new Update().unset("familyGroupId"));
        var simulation = simulate("2026-09");
        for (var receipt : List.of(left, pending, cash, cashAgain)) {
            var incident = java.util.stream.StreamSupport.stream(simulation.path("incidents").spliterator(), false)
                    .filter(row -> row.path("invoiceId").equals(receipt.path("id"))).findFirst();
            assertThat(incident).as("receipt incident for " + receipt.path("displayNumber")).isPresent();
            assertThat(incident.orElseThrow().path("displayNumber")).isEqualTo(receipt.path("displayNumber"));
            assertThat(incident.orElseThrow().path("code").asText()).isEqualTo(receipt == left || receipt == pending ? "MEMBER_NOT_ACTIVE" : "PAYMENT_METHOD_CHANGED");
        }
        assertThat(simulation.path("incidents")).filteredOn(row -> row.path("memberId").asText().equals("bosch"))
                .singleElement().satisfies(row -> assertThat(row.has("invoiceId") || row.has("displayNumber")).isFalse());
        var result = run("2026-09", simulation.path("id").asText());
        assertThat(result.path("skipped").findValuesAsText("memberId")).contains("roca", "puig").doesNotContain("serra-joan");
        assertThat(invoices()).anySatisfy(row -> {
            assertThat(row.getString("memberId")).isEqualTo("serra-joan");
            assertThat(row.getString("runId")).isEqualTo(result.at("/run/id").asText());
        });
    }
    @Test void T_12_11_fictionalSepaDebtorsHaveDistinctAccountsAndHolders() {
        var members = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("paymentMethod.iban").ne(null)), Document.class, "members");
        assertThat(members).hasSizeGreaterThan(5);
        assertThat(members.stream().map(row -> row.get("paymentMethod", Document.class).getString("iban")).distinct().count()).isEqualTo(members.size());
        assertThat(members.stream().map(row -> row.get("paymentMethod", Document.class).getString("holderName")).distinct().count()).isEqualTo(members.size());
    }
    @ParameterizedTest @ValueSource(strings = {"SER·{YYYY}", "SER_{YYYY}", "Xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "{YY}"})
    void R_12_12_D11RejectsAnInvalidInvoiceSeries(String pattern) throws Exception {
        var response = call(admin(put("/api/v1/parameters/billing.invoiceSeriesPattern").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("value", pattern, "version", 0)))));
        // CATALEG_ERRORS §1 is authoritative: PARAMETER_INVALID is 400, despite the task's illustrative 422.
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(json(response).path("code").asText()).isEqualTo("PARAMETER_INVALID");
        assertThat(json(response).path("details")).isEqualTo(mapper.valueToTree(Map.of("key", "billing.invoiceSeriesPattern", "type", "string")));
    }
    @Test void R_12_12_aStoredOverlongReceiptNumberFailsBeforeAnyRunWrites() throws Exception {
        var receipt = waiting("puig");
        String id = receipt.path("id").asText();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("displayNumber", "X".repeat(36)), "invoices");
        String simulation = simulate("2026-09").path("id").asText();
        var files = RemittancesIT.storedFiles();
        var response = call(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", simulation))));
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(json(response).path("code").asText()).isEqualTo("SEPA_NOT_CONFIGURED");
        assertThat(json(response).path("details")).isEqualTo(mapper.valueToTree(Map.of("reason", "IDENTIFIER", "field", "EndToEndId")));
        assertThat(invoices()).singleElement().satisfies(row -> assertThat(row.getString("status")).isEqualTo("PENDING"));
        for (String collection : List.of("collections", "remittances", "billing_runs")) {
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), collection)).isZero();
        }
        assertThat(member("puig", "nextInvoiceDate")).isEqualTo("2026-09-01");
        assertThat(RemittancesIT.storedFiles()).isEqualTo(files);
    }
    @Test void R_03_07_theMandateSequenceSurvivesLeavingSepaRepeatedly() throws Exception {
        for (int next : List.of(2, 3)) {
            ok(admin(patch("/api/v1/members/puig/payment-method").contentType("application/json")
                    .content("{\"type\":\"MANUAL\",\"manual\":{\"channel\":\"cash\"}}")), 200);
            // An unrelated MANUAL edit must preserve the hidden sequence too.
            ok(admin(patch("/api/v1/members/puig/payment-method").contentType("application/json")
                    .content("{\"type\":\"MANUAL\",\"manual\":{\"channel\":\"transfer\"}}")), 200);
            var manual = ok(admin(get("/api/v1/members/puig")), 200);
            assertThat(manual.toString()).doesNotContain("lastMandateSequence");
            ok(admin(patch("/api/v1/members/puig/payment-method").contentType("application/json").content(mapper.writeValueAsString(
                    Map.of("type", "SEPA_DD", "sepa", Map.of("iban", RemittancesIT.iban("00000000000000900208"), "holderName", "Eva Example"))))), 200);
            assertThat(mongo.findById("puig", Document.class, "members").get("paymentMethod", Document.class).getString("mandateRef"))
                    .isEqualTo("bill-a-208-" + next);
        }
    }
    @org.springframework.beans.factory.annotation.Autowired com.agilityhub.core.shared.application.BillingCensusAccess census;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test void R_03_07_cardSetupAlsoPreservesTheLastSepaMandateSequence() throws Exception {
        try (var tenant = com.agilityhub.core.shared.application.TenantContext.open(CLUB)) {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> census.saveCard("puig",
                    new com.agilityhub.core.shared.application.BillingCensusAccess.Card("cus_example", "pm_example", "4242", "visa", false)));
        }
        ok(admin(patch("/api/v1/members/puig/payment-method").contentType("application/json").content(mapper.writeValueAsString(
                Map.of("type", "SEPA_DD", "sepa", Map.of("iban", RemittancesIT.iban("00000000000000900208"), "holderName", "Eva Example"))))), 200);
        var response = ok(admin(get("/api/v1/members/puig")), 200);
        assertThat(response.toString()).doesNotContain("lastMandateSequence");
        assertThat(mongo.findById("puig", Document.class, "members").get("paymentMethod", Document.class).getString("mandateRef"))
                .isEqualTo("bill-a-208-2");
    }

    @Test void R_12_07_newChargesOutsideTheBilledDraftsDoNotMakeASimulationStale() throws Exception {
        change("xirau", new Update().set("leaveDate", "2026-08-31"));
        String simulation = simulate("2026-09").path("id").asText();
        clock.setInstant(NOW.plusSeconds(60));
        charge("ignored-incident", "bosch", "ignored-booking-1", "Example incident charge");
        charge("ignored-month", "xirau", "ignored-booking-2", "Example later charge");
        var result = run("2026-09", simulation);
        assertThat(result.at("/run/id").asText()).isNotBlank();
        for (String id : List.of("ignored-incident", "ignored-month")) {
            assertThat(mongo.findById(id, Document.class, "pending_charges").get("invoiceId")).isNull();
        }
    }
    @Test void R_12_07_anIneligibleWaitingReceiptDoesNotMakeASimulationStale() throws Exception {
        change("roca", new Update().set("status", "LEFT"));
        String simulation = simulate("2026-09").path("id").asText();
        // A legacy flagged receipt of a LEFT member appears without changing the billed plan.
        var receipt = waiting("puig");
        mongo.updateFirst(Query.query(Criteria.where("_id").is(receipt.path("id").asText())), new Update().set("memberId", "roca"), "invoices");
        assertThat(run("2026-09", simulation).at("/run/id").asText()).isNotBlank();
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, 28})
    void T_12_29b_simulationCollectionDateIsTheRunsDefault(int day) throws Exception {
        parameter(CLUB, "billing.sepa.collectionDayOfMonth", day);
        var simulation = simulate("2026-09");
        assertThat(simulation.at("/kpis/collectionDate").asText()).isEqualTo("2026-09-" + String.format("%02d", day == 0 ? 30 : day));
        assertThat(run("2026-09", simulation.path("id").asText()).at("/run/collectionDate")).isEqualTo(simulation.at("/kpis/collectionDate"));
        if (day == 1) {
            Files.createDirectories(Path.of("target/e8-t08"));
            Files.writeString(Path.of("target/e8-t08/simulation-kpis.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(simulation.path("kpis")) + "\n");
        }
    }
}

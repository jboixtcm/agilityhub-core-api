package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.sepa.Pain008Document;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E8-T03 (S12 WP-12-C) on E8-T02's fictional census of `bill-a` (25-08-2026 10:00 in Madrid): the pain.008 file of the run, its
 * golden file and its schema (T-12-11), the remittance endpoints (R-12-12, R-12-15), the rollback that keeps the file (T-12-13
 * SEPA half), the submission that ends the rollback (T-12-30 SEPA half), a club without SEPA (R-12-28, T-12-32) and one whose
 * SEPA has no creditor data (step 9), the new mandate of a changed account (S12 §7) and the self-authorising local link.
 */
class RemittancesIT extends BillingItSupport {
    static final Path GOLDEN = Path.of("src/test/resources/sepa/remesa-2026-09.golden.xml");
    static final Path ACTUAL = Path.of("target/sepa/remesa-2026-09.actual.xml");
    /** A full IBAN in a response: a country, two check digits and at least ten more digits, spaced or not (the creditor identifier has letters). */
    static final Pattern FULL_IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}(?: ?[0-9]){10,30}");

    JsonNode generate() throws Exception { return run("2026-09", simulate("2026-09").path("id").asText()); }
    MockHttpServletResponse download(String remittanceId) throws Exception {
        var link = ok(admin(get("/api/v1/remittances/" + remittanceId + "/file")), 200);
        assertThat(link.path("fileName").asText()).isEqualTo("remesa-" + mongo.findById(remittanceId, Document.class, "remittances").getString("period") + ".xml");
        // The local link authorises itself (CONVENCIONS_API §5, A31): no host, no bearer.
        return call(get(link.path("downloadUrl").asText()));
    }

    @Test void T_12_11_theFictionalCensusRemittanceIsTheGoldenFileValidatesCarriesTheFullIbanAndNoResponseDoes() throws Exception {
        var result = generate();
        String remittanceId = result.at("/remittance/id").asText();
        assertThat(result.at("/remittance/fileAvailable").asBoolean()).isTrue();
        assertThat(result.at("/remittance/xsdValidatedAt").asText()).isEqualTo("2026-08-25T08:00:00Z");
        assertThat(result.at("/remittance/xsdValidationSkipped").isMissingNode() || result.at("/remittance/xsdValidationSkipped").isNull()).isTrue();
        assertThat(result.at("/remittance/sequenceBreakdown/RCUR").asInt()).isEqualTo(4);
        assertThat(result.at("/remittance/sequenceBreakdown/FRST").asInt()).isZero();
        var response = download(remittanceId);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/xml");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"remesa-2026-09.xml\"");
        assertThat(response.getHeader("Content-Security-Policy")).contains("sandbox");
        byte[] xml = response.getContentAsByteArray();
        Files.createDirectories(ACTUAL.getParent()); Files.write(ACTUAL, xml);
        // The schema in use accepts it; the debtors' full IBAN is in the file and only there.
        assertThatCode(() -> Pain008Document.validate(xml)).doesNotThrowAnyException();
        String text = new String(xml, StandardCharsets.UTF_8);
        assertThat(text).contains("<IBAN>" + IBAN + "</IBAN>", "<NbOfTxs>4</NbOfTxs>", "<CtrlSum>204.00</CtrlSum>", "<SeqTp>RCUR</SeqTp>",
                "<ReqdColltnDt>2026-09-01</ReqdColltnDt>", "<MsgId>bill-a-2026-09-1</MsgId>").doesNotContain("FRST");
        assertThat(GOLDEN).as("the golden file (regenerate it only on purpose, from " + ACTUAL + ")").exists();
        assertThat(xml).as("byte for byte the golden file; the actual file is in " + ACTUAL).isEqualTo(Files.readAllBytes(GOLDEN));
        // T-12-11: no API response of any route carries a full IBAN — the debtors' or the creditor's.
        var responses = new ArrayList<String>();
        responses.add(call(admin(get("/api/v1/invoices"))).getContentAsString());
        for (var invoice : invoices()) { responses.add(call(admin(get("/api/v1/invoices/" + invoice.getString("_id")))).getContentAsString()); }
        responses.add(call(admin(get("/api/v1/remittances"))).getContentAsString());
        responses.add(call(admin(get("/api/v1/remittances/" + remittanceId))).getContentAsString());
        responses.add(call(admin(get("/api/v1/remittances/" + remittanceId + "/file"))).getContentAsString());
        responses.add(call(admin(get("/api/v1/billing/periods/2026-09"))).getContentAsString());
        responses.add(result.toString());
        for (String member : List.of("puig", "serra-laura", "serra-joan", "mas", "torres")) {
            responses.add(call(as(get("/api/v1/me/invoices"), CLUB, "MEMBER", member)).getContentAsString());
        }
        assertThat(responses).hasSize(19).allSatisfy(body -> {
            assertThat(body).doesNotContain(IBAN, IBAN.replace("4321", "9876"));
            assertThat(FULL_IBAN.matcher(body).find()).as(body).isFalse();
        });
        // The remittance document keeps the creditor snapshot (masked in every response) and the file key (never published).
        var stored = mongo.findById(remittanceId, Document.class, "remittances");
        assertThat(stored.getString("fileKey")).startsWith("remittances/bill-a/2026-09/bill-a-2026-09-1-");
        assertThat(stored.get("xsdValidatedAt")).isNotNull();
        assertThat(responses).noneMatch(body -> body.contains(stored.getString("fileKey")));
    }

    @Test void T_12_13_aRolledBackRemittanceKeepsItsFileItsLinkAndItsRow() throws Exception {
        var result = generate();
        String remittanceId = result.at("/remittance/id").asText(), runId = result.at("/run/id").asText();
        byte[] before = download(remittanceId).getContentAsByteArray();
        ok(admin(keyed(post("/api/v1/billing/runs/" + runId + "/rollback"), Map.of("reason", "Preu equivocat", "confirmation", "RETROCEDIR"))), 200);
        var rolledBack = ok(admin(get("/api/v1/remittances/" + remittanceId)), 200);
        assertThat(rolledBack.path("status").asText()).isEqualTo("ROLLED_BACK");
        assertThat(rolledBack.path("fileAvailable").asBoolean()).isTrue();
        assertThat(download(remittanceId).getContentAsByteArray()).isEqualTo(before);
        // A new generation of the month: a second message id, both remittances listed, newest first.
        var again = generate();
        assertThat(again.at("/remittance/messageId").asText()).isEqualTo("bill-a-2026-09-2");
        var page = ok(admin(get("/api/v1/remittances")), 200);
        assertThat(page.path("totalItems").asLong()).isEqualTo(2);
        assertThat(page.path("items").findValuesAsText("status")).containsExactlyInAnyOrder("GENERATED", "ROLLED_BACK");
        assertThat(page.path("items")).allSatisfy(row -> assertThat(row.path("fileAvailable").asBoolean()).isTrue());
        var filtered = ok(admin(get("/api/v1/remittances").param("filter", "status:eq:ROLLED_BACK")), 200);
        assertThat(filtered.path("items")).singleElement().satisfies(row -> {
            assertThat(row.path("id").asText()).isEqualTo(remittanceId);
            assertThat(row.path("messageId").asText()).isEqualTo("bill-a-2026-09-1");
            assertThat(row.path("count").asInt()).isEqualTo(4);
            assertThat(row.at("/total/amountMinor").asLong()).isEqualTo(20_400);
            assertThat(row.path("requestedCollectionDate").asText()).isEqualTo("2026-09-01");
        });
        assertThat(ok(admin(get("/api/v1/remittances").param("filter", "period:eq:2026-10")), 200).path("items")).isEmpty();
    }

    @Test @AuditCovers(AuditAction.REMITTANCE_SUBMITTED)
    void T_12_30_aSubmittedRemittanceEndsTheRollbackAndTheSubmissionIsAuditedOnce() throws Exception {
        var result = generate();
        String remittanceId = result.at("/remittance/id").asText(), runId = result.at("/run/id").asText();
        clock.setInstant(Instant.parse("2026-08-26T09:00:00Z"));
        // Not after today, not before the remittance's own day (club-local).
        error(admin(keyed(post("/api/v1/remittances/" + remittanceId + "/submission"), Map.of("submittedAt", "2026-08-27"))), 400, "VALIDATION_ERROR");
        error(admin(keyed(post("/api/v1/remittances/" + remittanceId + "/submission"), Map.of("submittedAt", "2026-08-24"))), 400, "VALIDATION_ERROR");
        String key = UUID.randomUUID().toString();
        var request = post("/api/v1/remittances/" + remittanceId + "/submission").header("Idempotency-Key", key).contentType("application/json")
                .content("{\"submittedAt\":\"2026-08-26\"}");
        var submitted = ok(admin(request), 200);
        assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(submitted.path("submittedAt").asText()).isEqualTo("2026-08-25T22:00:00Z");
        assertThat(submitted.path("submittedByAccountId").asText()).isEqualTo("bill-admin");
        // The same key answers the same remittance; another submission is 409 INVALID_STATE.
        assertThat(ok(admin(post("/api/v1/remittances/" + remittanceId + "/submission").header("Idempotency-Key", key).contentType("application/json")
                .content("{\"submittedAt\":\"2026-08-26\"}")), 200)).isEqualTo(submitted);
        error(admin(keyed(post("/api/v1/remittances/" + remittanceId + "/submission"), Map.of("submittedAt", "2026-08-26"))), 409, "INVALID_STATE");
        var audit = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("REMITTANCE_SUBMITTED")), Document.class, "audit_entries");
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.getString("entityType")).isEqualTo("Remittance");
            assertThat(entry.getString("entityId")).isEqualTo(remittanceId);
            assertThat(entry.getList("changes", Document.class)).extracting(change -> change.getString("path")).containsExactlyInAnyOrder("status", "submittedAt");
            assertThat(entry.get("details", Document.class).getString("submittedOn")).isEqualTo("2026-08-26");
            assertThat(entry.toJson()).doesNotContain(IBAN.replace("4321", "9876"));
        });
        // T-12-30: from now on the run cannot be rolled back.
        var run = ok(admin(get("/api/v1/billing/runs/" + runId)), 200);
        assertThat(run.path("rollbackable").asBoolean()).isFalse();
        assertThat(run.path("rollbackBlockers").toString()).isEqualTo("[\"REMITTANCE_SUBMITTED\"]");
        var refused = call(admin(keyed(post("/api/v1/billing/runs/" + runId + "/rollback"), Map.of("reason", "Massa tard", "confirmation", "RETROCEDIR"))));
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(json(refused).path("code").asText()).isEqualTo("RUN_NOT_ROLLBACKABLE");
        assertThat(json(refused).at("/details/reasons").toString()).isEqualTo("[\"REMITTANCE_SUBMITTED\"]");
        // The file stays downloadable and the list shows the submission.
        assertThat(download(remittanceId).getStatus()).isEqualTo(200);
        assertThat(ok(admin(get("/api/v1/remittances").param("filter", "status:eq:SUBMITTED")), 200).at("/items/0/submittedAt").asText())
                .isEqualTo("2026-08-25T22:00:00Z");
    }

    /**
     * R-12-19 and R-12-07 (the E8-T02 review's finding 1): a pain.008 amount is at least 0.01, so a zero or negative adjustment
     * with `includeInNextRun` never rides a remittance — it waits as `PENDING` for R-12-16 — and never blocks the run.
     */
    @Test void R_12_19_onlyAPositiveManualReceiptRidesTheNextRemittance() throws Exception {
        var positive = ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "puig", "includeInNextRun", true, "note", "Quota d'agost pendent",
                "lines", List.of(Map.of("description", "Quota d'agost pendent", "base", Map.of("amountMinor", 2500, "currency", "EUR"), "taxPercent", 0))))), 201);
        // E8-T07 step 1: the flag is refused with a negative total at creation; this is a receipt stored with it before that fix.
        var negative = ok(admin(keyed(post("/api/v1/invoices"), Map.of("memberId", "torres", "note", "Ajust",
                "lines", List.of(Map.of("description", "Ajust quota setembre", "base", Map.of("amountMinor", -3000, "currency", "EUR"), "taxPercent", 0))))), 201);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(negative.path("id").asText())),
                new org.springframework.data.mongodb.core.query.Update().set("includeInNextRun", true), "invoices");
        var result = generate();
        assertThat(result.at("/remittance/count").asInt()).isEqualTo(5);
        assertThat(result.at("/remittance/total/amountMinor").asLong()).isEqualTo(20_400 + 2_500);
        assertThat(mongo.findById(positive.path("id").asText(), Document.class, "invoices").getString("status")).isEqualTo("COLLECTING");
        var waiting = mongo.findById(negative.path("id").asText(), Document.class, "invoices");
        assertThat(waiting.getString("status")).isEqualTo("PENDING");
        assertThat(waiting.get("remittanceId")).isNull();
        String xml = download(result.at("/remittance/id").asText()).getContentAsString(StandardCharsets.UTF_8);
        assertThat(xml).contains("<InstdAmt Ccy=\"EUR\">25.00</InstdAmt>", "<Ustrd>Quota d'agost pendent</Ustrd>").doesNotContain("-30.00");
    }

    @Test void T_12_32_R_12_28_aClubWithoutSepaGeneratesNoRemittanceAndListsNone() throws Exception {
        club(CLUB, HOST, "Europe/Madrid", List.of(com.agilityhub.core.platform.application.Module.values()), providers(false, true, false));
        var result = generate();
        assertThat(result.path("remittance").isNull()).isTrue();
        var page = ok(admin(get("/api/v1/remittances")), 200);
        assertThat(page.path("items")).isEmpty();
        assertThat(page.path("totalItems").asLong()).isZero();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "remittances")).isZero();
    }

    @Test void R_12_28_sepaWithoutItsCreditorDataIsProviderDisabledInTheSimulationAnd422AtRunTimeWritingNothing() throws Exception {
        club(CLUB, HOST, "Europe/Madrid", List.of(com.agilityhub.core.platform.application.Module.values()),
                Map.of("SEPA_XML", Map.of("enabled", true, "creditorName", "Club Agility Facturació"), "MANUAL", Map.of("enabled", true)));
        var simulation = simulate("2026-09");
        var disabled = new ArrayList<String>();
        simulation.path("incidents").forEach(incident -> { if (incident.path("code").asText().equals("PROVIDER_DISABLED")) { disabled.add(incident.path("memberId").asText()); } });
        // The four direct debits, next to Sara Sala's card (STRIPE is off: E8-T02's own PROVIDER_DISABLED).
        assertThat(disabled).containsExactlyInAnyOrder("mas", "puig", "serra-laura", "torres", "sala");
        assertThat(simulation.at("/kpis/byProvider/SEPA_XML/count").asInt()).isZero();
        var files = storedFiles();
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", simulation.path("id").asText()))), 422, "SEPA_NOT_CONFIGURED");
        assertNothingWritten(files);
    }

    @Test void R_12_11_aFileTheSchemaRefusesAbortsTheRunAndWritesNothing() throws Exception {
        club(CLUB, HOST, "Europe/Madrid", List.of(com.agilityhub.core.platform.application.Module.values()), Map.of("SEPA_XML",
                Map.of("enabled", true, "creditorName", "Club", "creditorId", "NOT AN IDENTIFIER", "iban", IBAN.replace("4321", "9876")), "MANUAL", Map.of("enabled", true)));
        var simulation = simulate("2026-09").path("id").asText();
        var files = storedFiles();
        var refused = call(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", simulation))));
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(json(refused).path("code").asText()).isEqualTo("SEPA_NOT_CONFIGURED");
        assertThat(json(refused).at("/details/reason").asText()).isEqualTo("SCHEMA");
        assertThat(refused.getContentAsString()).doesNotContain(IBAN, "NOT AN IDENTIFIER");
        assertNothingWritten(files);
    }
    /** R-12-11: a refused run leaves no invoice, collection, remittance, run, moved date or stored file behind. */
    private void assertNothingWritten(Set<Path> filesBefore) throws Exception {
        for (String collection : List.of("invoices", "collections", "remittances", "billing_runs")) {
            assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), collection)).as(collection).isZero();
        }
        assertThat(member("puig", "nextInvoiceDate")).isEqualTo("2026-09-01");
        assertThat(storedFiles()).isEqualTo(filesBefore);
    }
    /** The remittance files the local store holds for the club (the test profile's `exports.local-directory`). */
    static Set<Path> storedFiles() throws Exception {
        var root = Path.of("target/test-exports/remittances/" + CLUB);
        if (!Files.exists(root)) { return Set.of(); }
        try (var walk = Files.walk(root)) { return walk.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toSet()); }
    }

    @Test void S12_7_aChangedAccountIsANewMandateWhoseFirstDebitIsFrstWithUseFrst() throws Exception {
        parameter(CLUB, "billing.sepa.useFrst", true);
        // September: every mandate is debited for the first time, and the remittance goes to the bank.
        var september = generate();
        assertThat(september.at("/remittance/sequenceBreakdown/FRST").asInt()).isEqualTo(4);
        clock.setInstant(Instant.parse("2026-08-26T09:00:00Z"));
        ok(admin(keyed(post("/api/v1/remittances/" + september.at("/remittance/id").asText() + "/submission"), Map.of("submittedAt", "2026-08-26"))), 200);
        // Eva Puig gives another account: a new mandate (sequence 2, signed today); correcting the holder's data keeps it (R-03-07).
        String iban = iban("00000000000000000208");
        ok(admin(patch("/api/v1/members/puig/payment-method").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("type", "SEPA_DD", "sepa", Map.of("iban", iban))))), 200);
        var puig = mongo.findById("puig", Document.class, "members").get("paymentMethod", Document.class);
        assertThat(puig.getString("mandateRef")).isEqualTo("bill-a-208-2");
        assertThat(puig.getDate("mandateSignedAt").toInstant()).isEqualTo(Instant.parse("2026-08-26T09:00:00Z"));
        ok(admin(patch("/api/v1/members/puig/payment-method").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("type", "SEPA_DD", "sepa", Map.of("holderTaxId", "12345678Z", "holderName", "Eva Puig Example"))))), 200);
        assertThat(mongo.findById("puig", Document.class, "members").get("paymentMethod", Document.class).getString("mandateRef")).isEqualTo("bill-a-208-2");
        // Rosa Vidal paid cash and now signs a direct debit: her first mandate.
        ok(admin(patch("/api/v1/members/vidal/payment-method").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("type", "SEPA_DD", "sepa", Map.of("iban", iban("00000000000000000213"), "holderName", "Rosa Vidal"))))), 200);
        var vidal = mongo.findById("vidal", Document.class, "members").get("paymentMethod", Document.class);
        assertThat(vidal.getString("mandateRef")).isEqualTo("bill-a-213-1");
        assertThat(vidal.getDate("mandateSignedAt").toInstant()).isEqualTo(Instant.parse("2026-08-26T09:00:00Z"));
        // October (Mia's single classes are all billed, so she has no receipt): Eva's new mandate is FRST; Laura's and Teresa's, sent
        // with September's remittance, are RCUR.
        clock.setInstant(Instant.parse("2026-09-25T08:00:00Z"));
        var october = run("2026-10", simulate("2026-10").path("id").asText());
        assertThat(october.at("/remittance/sequenceBreakdown/FRST").asInt()).isEqualTo(1);
        assertThat(october.at("/remittance/sequenceBreakdown/RCUR").asInt()).isEqualTo(2);
        String xml = download(october.at("/remittance/id").asText()).getContentAsString(StandardCharsets.UTF_8);
        String frst = xml.substring(xml.indexOf("<SeqTp>FRST</SeqTp>"), xml.indexOf("<SeqTp>RCUR</SeqTp>"));
        assertThat(frst).contains("<MndtId>bill-a-208-2</MndtId>", "<DtOfSgntr>2026-08-26</DtOfSgntr>", "<IBAN>" + iban + "</IBAN>").doesNotContain("bill-a-207-1");
    }

    @Test void T_12_21_theLocalLinkAuthorisesItselfOnlyWithItsOwnSignatureAndTime() throws Exception {
        String remittanceId = generate().at("/remittance/id").asText();
        String url = ok(admin(get("/api/v1/remittances/" + remittanceId + "/file")), 200).path("downloadUrl").asText();
        assertThat(url).startsWith("/api/v1/remittances/files/bill-a/" + remittanceId + "?expires=");
        assertThat(call(get(url)).getStatus()).isEqualTo(200);
        // Another club in the path, a tampered signature or an expired link → 403; the bearer of another club changes nothing.
        assertThat(call(get(url.replace("/files/bill-a/", "/files/bill-b/"))).getStatus()).isEqualTo(403);
        assertThat(call(get(url + "0")).getStatus()).isEqualTo(403);
        assertThat(call(as(get(url), OTHER, "ADMIN", null)).getStatus()).isEqualTo(200);
        clock.setInstant(NOW.plusSeconds(301));
        assertThat(call(get(url)).getStatus()).isEqualTo(403);
        // Issuing the link is the audited file access (DATA_EXPORTED); MEMBER and INSTRUCTOR never get one; another club's remittance is 404.
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("DATA_EXPORTED").and("entityId").is(remittanceId)), "audit_entries"))
                .isEqualTo(1);
        error(as(get("/api/v1/remittances/" + remittanceId + "/file"), CLUB, "MEMBER", "puig"), 403, "FORBIDDEN");
        error(as(get("/api/v1/remittances/" + remittanceId + "/file"), CLUB, "INSTRUCTOR", null), 403, "FORBIDDEN");
        error(as(get("/api/v1/remittances/" + remittanceId), OTHER, "ADMIN", null), 404, "NOT_FOUND");
        error(as(keyed(post("/api/v1/remittances/" + remittanceId + "/submission"), Map.of("submittedAt", "2026-08-25")), OTHER, "ADMIN", null), 404, "NOT_FOUND");
    }

    /** A fictional Spanish IBAN for {@code bban} (20 digits) with valid check digits: the census validates them (mod 97). */
    static String iban(String bban) {
        int check = 98 - new BigInteger(bban + "142800").mod(BigInteger.valueOf(97)).intValue();
        return String.format("ES%02d%s", check, bban);
    }
}

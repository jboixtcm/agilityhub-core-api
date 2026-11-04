package com.agilityhub.core.payments.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.*;
import javax.xml.xpath.XPathFactory;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class InvoiceConceptsIT extends BillingItSupport {
    private static final String CONCEPTS = "/api/v1/billing/concepts";
    private Map<String,Object> rule(String text, long amount, List<String> members, String end) {
        var body = new HashMap<String,Object>();
        body.put("description", text); body.put("amount", Map.of("amountMinor", amount, "currency", "EUR"));
        body.put("firstPeriod", "2026-09"); body.put("endsOn", end); body.put("taxPercent", 0);
        body.put("allMembers", members.isEmpty()); body.put("memberIds", members);
        return body;
    }

    private List<String> descriptions(JsonNode sim, String member) {
        for (var preview : sim.path("invoicesPreview")) {
            if (member.equals(preview.path("memberId").asText())) {
                return preview.path("lines").findValuesAsText("description");
            }
        }
        return List.of();
    }

    @Test void T_12_concepts_repeatOnlyWithinDates_andReachInvoiceBankAndExcel() throws Exception {
        var earlier = simulate("2026-09");
        var once = rule("Locker", 1000, List.of("puig"), null);
        var idempotent = admin(keyed(post(CONCEPTS), once));
        String id = ok(idempotent, 201).path("id").asText();
        assertThat(ok(idempotent, 201).path("id").asText()).isEqualTo(id);
        ok(admin(keyed(post(CONCEPTS),rule("Discount", -350, List.of("puig","serra-joan"), "2026-10-04"))),201);
        ok(admin(keyed(post(CONCEPTS),rule("Club fee", 200, List.of(), null))),201);
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period","2026-09","simulationId",earlier.path("id").asText()))),409,"SIMULATION_STALE");
        var sep = simulate("2026-09");
        assertThat(descriptions(sep,"puig")).containsOnlyOnce("Locker","Discount","Club fee");
        assertThat(descriptions(sep,"serra-laura")).containsOnlyOnce("Discount").containsSubsequence("Club fee","Club fee").doesNotContain("Locker");
        assertThat(descriptions(sep,"torres")).contains("Club fee").doesNotContain("Locker","Discount");
        var generated = run("2026-09", sep.path("id").asText());
        var receipt = invoices().stream().filter(r -> "puig".equals(r.get("memberId"))).findFirst().orElseThrow();
        long cents = receipt.get("total", Document.class).getLong("amountMinor");
        assertThat(cents).isEqualTo(6850);
        String number = receipt.getString("displayNumber");
        var link = ok(admin(get("/api/v1/remittances/" + generated.at("/remittance/id").asText() + "/file")),200);
        var xml = call(get(link.path("downloadUrl").asText())).getContentAsByteArray();
        var debit = XPathFactory.newInstance().newXPath().evaluate(
                "//*[local-name()='DrctDbtTxInf'][.//*[local-name()='EndToEndId']='" + number + "']/*[local-name()='InstdAmt']",
                new InputSource(new ByteArrayInputStream(xml)));
        assertThat(new BigDecimal(debit)).isEqualByComparingTo("68.50");

        String spreadsheetPath = "/api/v1/remittances/"+generated.at("/remittance/id").asText()+"/spreadsheet";
        error(as(get(spreadsheetPath),CLUB,"MEMBER","puig"),403,"FORBIDDEN");
        error(as(get(spreadsheetPath),OTHER,"ADMIN",null),404,"NOT_FOUND");
        var bank = call(admin(get(spreadsheetPath).header("Accept-Language","ca")));
        assertThat(bank.getStatus()).isEqualTo(200);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bank.getContentAsByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(generated.at("/remittance/count").asInt());
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Referència del rebut");
            var matched = 0;
            BigDecimal total = BigDecimal.ZERO;
            for (int i=1; i<=sheet.getLastRowNum(); i++) {
                var row = sheet.getRow(i);
                var value = BigDecimal.valueOf(row.getCell(6).getNumericCellValue());
                total = total.add(value);
                if (number.equals(row.getCell(0).getStringCellValue())) {
                    matched++;
                    assertThat(value).isEqualByComparingTo(debit);
                    assertThat(row.getCell(2).getStringCellValue()).isEqualTo(debtorIban(208));
                }
            }
            assertThat(matched).isOne();
            assertThat(total.movePointRight(2).longValueExact()).isEqualTo(generated.at("/remittance/total/amountMinor").asLong());
        }

        var file = call(admin(get("/api/v1/billing/exports").param("period","2026-09").param("format","xlsx")));
        assertThat(file.getStatus()).isEqualTo(200);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(file.getContentAsByteArray()))) {
            var amounts = new LinkedHashMap<String,BigDecimal>();
            for (var row : workbook.getSheetAt(0)) {
                if (row.getCell(0) == null || !number.equals(row.getCell(0).toString())) { continue; }
                amounts.put(row.getCell(6).toString(), new BigDecimal(row.getCell(10).toString().replace(',', '.')));
            }
            assertThat(amounts.get("Discount")).isEqualByComparingTo("-3.50");
            assertThat(amounts.get("Locker")).isEqualByComparingTo("10.00");
            assertThat(amounts.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("68.50");
        }
        var oct = simulate("2026-10");
        assertThat(descriptions(oct,"puig")).containsOnlyOnce("Discount").doesNotContain("Locker","Club fee");
        run("2026-10",oct.path("id").asText());
        assertThat(descriptions(simulate("2026-11"),"puig")).doesNotContain("Discount","Locker","Club fee");
    }

    @Test void T_12_zeroAndNegativeBalancesStayOutOfBank_andRollbackReissuesOnce() throws Exception {
        ok(admin(keyed(post(CONCEPTS),rule("Full discount", -6000, List.of("puig"), null))),201);
        ok(admin(keyed(post(CONCEPTS),rule("Credit", -4000, List.of("torres"), null))),201);
        var result = run("2026-09",simulate("2026-09").path("id").asText());
        for (var r : invoices()) {
            if (Set.of("torres","puig").contains(r.get("memberId"))) {
                assertThat(r.get("paymentMethod",Document.class).getString("type")).isEqualTo("MANUAL");
                assertThat(r.get("remittanceId")).isNull();
                assertThat(r.get("total",Document.class).getLong("amountMinor")).isEqualTo("puig".equals(r.get("memberId")) ? 0L : -1000L);
            }
        }
        ok(admin(keyed(post("/api/v1/billing/runs/"+result.at("/run/id").asText()+"/rollback"),
                Map.of("reason","Fixture correction","confirmation","RETROCEDIR"))),200);
        run("2026-09",simulate("2026-09").path("id").asText());
        var active = invoices().stream().filter(r -> "puig".equals(r.get("memberId")) && !"CANCELLED".equals(r.get("status"))).toList();
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().getList("lines",Document.class)).filteredOn(l -> "Full discount".equals(l.get("description"))).hasSize(1);
    }

    @Test void T_12_adjustmentWithoutPeriodicFee_preservesTheNextFeeDate() throws Exception {
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is("puig")),
                new org.springframework.data.mongodb.core.query.Update().set("nextInvoiceDate","2026-12-01"),"members");
        var supplement = rule("Equipment",1210,List.of("puig"),null);
        supplement.put("taxPercent",21);
        ok(admin(keyed(post(CONCEPTS),supplement)),201);
        var sim = simulate("2026-09");
        assertThat(descriptions(sim,"puig")).containsExactly("Equipment");
        run("2026-09",sim.path("id").asText());
        var invoice = invoices().stream().filter(r -> "puig".equals(r.get("memberId"))).findFirst().orElseThrow();
        assertThat(invoice.get("base",Document.class).getLong("amountMinor")).isEqualTo(1000);
        assertThat(invoice.get("tax",Document.class).getLong("amountMinor")).isEqualTo(210);
        assertThat(invoice.get("total",Document.class).getLong("amountMinor")).isEqualTo(1210);
        assertThat(mongo.findById("puig", Document.class, "members").getString("nextInvoiceDate")).isEqualTo("2026-12-01");
        error(admin(keyed(post(CONCEPTS),supplement)),409,"RUN_EXISTS");
    }

    @Test void T_12_conceptScopeValidationAndCancellation() throws Exception {
        var body = rule("Season", 100, List.of("puig"),"2026-12-01");
        for (String role : List.of("MEMBER","INSTRUCTOR")) {
            error(as(keyed(post(CONCEPTS),body),CLUB,role,"puig"),403,"FORBIDDEN");
            error(as(get(CONCEPTS),CLUB,role,"puig"),403,"FORBIDDEN");
        }
        String id = ok(admin(keyed(post(CONCEPTS),body)),201).path("id").asText();
        assertThat(ok(as(get(CONCEPTS),OTHER,"ADMIN",null),200)).isEmpty();
        error(as(delete(CONCEPTS+"/"+id),OTHER,"ADMIN",null),404,"NOT_FOUND");
        error(admin(keyed(post(CONCEPTS),rule("Bad date",100,List.of(),"2026-08-31"))),400,"VALIDATION_ERROR");
        error(admin(keyed(post(CONCEPTS),rule("Unknown",100,List.of("unknown"),null))),404,"NOT_FOUND");
        body.put("allMembers",true);
        error(admin(keyed(post(CONCEPTS),body)),400,"VALIDATION_ERROR");
        var before = simulate("2026-09");
        ok(admin(delete(CONCEPTS+"/"+id)),204);
        error(admin(keyed(post("/api/v1/billing/runs"),Map.of("period","2026-09","simulationId",before.path("id").asText()))),409,"SIMULATION_STALE");
        assertThat(descriptions(simulate("2026-09"),"puig")).doesNotContain("Season");
    }
}

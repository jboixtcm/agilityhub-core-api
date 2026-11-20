package com.agilityhub.core.payments.api;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class OutstandingBalancesIT extends BillingItSupport {
    static final String URL = "/api/v1/billing/outstanding-balances";
    private Document money(int amount) { return new Document("currency", "EUR").append("amountMinor", amount); }

    @Test void T_12_outstandingAmountsSpanPeriodsAndKeepInFlightMoneySeparate() throws Exception {
        for (var status : java.util.List.of("PENDING","COLLECTING","FAILED","CANCELLED","PAID")) {
            mongo.getCollection("invoices").insertOne(new Document("clubId", CLUB).append("series", "TEST-" + status)
                    .append("number", 1).append("displayNumber", status).append("memberId", "vidal").append("status", status).append("total", money(1500)));
        }
        for (var status : java.util.List.of("DUE","PARTIAL","CHECKOUT_PENDING","REFUNDED","CANCELLED","PAID")) {
            mongo.getCollection("upfront_payments").insertOne(new Document("clubId", CLUB).append("memberId", "vidal").append("status", status)
                    .append("amountDue", money(1000)).append("amountPaid", money(status.equals("PARTIAL") ? 500 : 0)));
        }
        var result = ok(admin(get(URL)),200);
        var row = java.util.stream.StreamSupport.stream(result.spliterator(), false).filter(r -> "vidal".equals(r.path("memberId").asText())).findFirst().orElseThrow();
        assertThat(row.path("memberName").asText()).contains("Rosa", "Vidal");
        assertThat(row.at("/invoices/amountMinor").asLong()).isEqualTo(3000);
        assertThat(row.at("/upfront/amountMinor").asLong()).isEqualTo(1500);
        assertThat(row.at("/processing/amountMinor").asLong()).isEqualTo(2500);
        assertThat(ok(as(get(URL), OTHER, "ADMIN", null),200)).isEmpty();
        assertThat(call(as(get(URL), CLUB,"MEMBER","vidal")).getStatus()).isEqualTo(403);
        assertThat(call(as(get(URL), CLUB,"INSTRUCTOR","vidal")).getStatus()).isEqualTo(403);
    }
}

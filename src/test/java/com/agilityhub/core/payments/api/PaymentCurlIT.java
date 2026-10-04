package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.*;
import com.agilityhub.core.platform.application.Module;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import static org.assertj.core.api.Assertions.*;

/** Real curl/TCP, JWT verification and Mongo transactions; all records belong to the disposable Testcontainers database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "shared.scheduling.enabled=false")
class PaymentCurlIT extends BillingItSupport {
    @LocalServerPort int port;
    @Autowired JwtEncoder encoder;
    @Value("${identity.issuer}") String issuer;
    @Autowired ProviderSecretVault vault;
    @Autowired FakePaymentProvider fake;
    static final String SECRET = "whsec_example_curl";
    String access;
    @Test void T_12_15_T_12_17_curlChargeFailureRetrySuccessDuplicateAndRefund() throws Exception {
        for (String name : List.of("members", "payment_operations", "stripe_events")) { mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), name); }
        fake.reset();
        club(CLUB, HOST, "Europe/Madrid", List.of(Module.values()), Map.of("STRIPE", Map.of("enabled", true, "mode", "test",
                "webhookSecretEnc", vault.encrypt(SECRET, CLUB, "STRIPE", "webhookSecretEnc"))));
        member("curl-member", 301, "Curl", "Example", CardPaymentsIT.validCard("curl-member"), "abonat");
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("curl-admin").audience(List.of("clubs-admin"))
                .issuedAt(clock.instant()).expiresAt(clock.instant().plusSeconds(900)).claim("clubId", CLUB).claim("roles", List.of("ADMIN")).build();
        access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        var generated = run("2026-09", simulate("2026-09").path("id").asText());
        String run = generated.at("/run/id").asText(), invoice = invoices().getFirst().getString("_id");
        curl("POST", "/api/v1/billing/runs/" + run + "/card-charges", Map.of(), 202, null);
        var first = mongo.findOne(Query.query(Criteria.where("invoiceId").is(invoice)), Document.class, "collections");
        event("evt_curl_failed", "payment_intent.payment_failed", Map.of("id", first.getString("providerRef")));
        var failed = curl("GET", "/api/v1/invoices/" + invoice, null, 200, null);
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        curl("POST", "/api/v1/invoices/" + invoice + "/retry", Map.of("version", failed.path("version").asLong()), 202, null);
        var second = mongo.findOne(Query.query(Criteria.where("invoiceId").is(invoice).and("attempt").is(2)), Document.class, "collections");
        var object = Map.<String, Object>of("id", second.getString("providerRef"));
        event("evt_curl_paid", "payment_intent.succeeded", object); event("evt_curl_paid", "payment_intent.succeeded", object);
        event("evt_curl_late_failure", "payment_intent.payment_failed", object);
        assertThat(curl("GET", "/api/v1/invoices/" + invoice, null, 200, null).path("status").asText()).isEqualTo("PAID");
        curl("POST", "/api/v1/invoices/" + invoice + "/refund", Map.of("reason", "Smoke refund"), 202, null);
        var refund = mongo.findOne(Query.query(Criteria.where("targetId").is(invoice).and("kind").is("REFUND_INVOICE")), Document.class, "payment_operations");
        event("evt_curl_refunded", "charge.refunded", Map.of("id", "ch_example", "payment_intent", second.getString("providerRef"), "currency", "eur",
                "refunds", Map.of("data", List.of(Map.of("id", refund.getString("resultId"), "amount", 6000, "status", "succeeded")))));
        var paid = curl("GET", "/api/v1/invoices/" + invoice, null, 200, null);
        assertThat(paid.path("status").asText()).isEqualTo("PAID"); assertThat(paid.at("/refundedTotal/amountMinor").asLong()).isEqualTo(6000);
        for (var call : fake.calls()) { System.out.println("FakePaymentProvider operation=" + call.operation() + " idempotencyKey=" + shortId(call.key())); }
        for (var row : mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "stripe_events")) {
            System.out.println("StripeEvent eventId=" + row.getString("eventId") + " type=" + row.getString("type") + " outcome=" + row.getString("outcome"));
        }
        assertThat(mongo.count(Query.query(Criteria.where("eventId").is("evt_curl_paid")), "stripe_events")).isEqualTo(1);
        assertThat(mongo.findById("evt_curl_late_failure", Document.class, "stripe_events").getString("outcome")).isEqualTo("IGNORED");
        System.out.println("PASS curl sequence: charge -> failed webhook -> invoice FAILED -> retry -> success/duplicate/late failure -> refund -> invoice PAID, refundedTotal=6000 EUR");
    }
    void event(String id, String type, Map<String, Object> object) throws Exception {
        String body = mapper.writeValueAsString(Map.of("id", id, "type", type, "created", clock.instant().getEpochSecond(), "data", Map.of("object", object)));
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "t=" + clock.instant().getEpochSecond() + ",v1=" + HexFormat.of().formatHex(mac.doFinal((clock.instant().getEpochSecond() + "." + body).getBytes(StandardCharsets.UTF_8)));
        curl("POST", "/webhooks/stripe/" + CLUB, body, 200, signature);
    }
    JsonNode curl(String method, String path, Object body, int expected, String signature) throws Exception {
        Path input = Files.createTempFile("payment-curl-", ".json"), output = Files.createTempFile("payment-curl-", ".response");
        try {
            String config = "url = \"http://127.0.0.1:" + port + path + "\"\nheader = \"Host: " + HOST + "\"\n";
            if (signature == null) { config += "header = \"Authorization: Bearer " + access + "\"\n"; }
            else { config += "header = \"Stripe-Signature: " + signature + "\"\n"; }
            if (signature == null && method.equals("POST")) { config += "header = \"Idempotency-Key: " + UUID.randomUUID() + "\"\n"; }
            var args = new ArrayList<>(List.of("curl", "--silent", "--show-error", "--noproxy", "*", "--max-time", "20", "-X", method,
                    "--config", "-", "--output", output.toString(), "--write-out", "%{http_code}"));
            if (body != null) { Files.writeString(input, body instanceof String raw ? raw : mapper.writeValueAsString(body)); args.addAll(List.of("-H", "Content-Type: application/json", "--data-binary", "@" + input)); }
            var process = new ProcessBuilder(args).redirectErrorStream(true).start();
            try (var stdin = process.getOutputStream()) { stdin.write(config.getBytes(StandardCharsets.UTF_8)); }
            String status = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).isZero(); assertThat(status).as("curl " + method + " " + path).isEqualTo(Integer.toString(expected));
            System.out.println("curl -X " + method + " " + path.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27,}", "[id truncated]") + " -> " + status);
            String response = Files.readString(output); return response.isBlank() ? mapper.createObjectNode() : mapper.readTree(response);
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output); }
    }
    static String shortId(String id) { return id.substring(0, Math.min(8, id.length())) + "...[truncated]" + (id.endsWith(":2") ? ":2" : ""); }
}

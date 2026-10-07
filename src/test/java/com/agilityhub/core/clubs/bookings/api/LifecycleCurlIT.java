package com.agilityhub.core.clubs.bookings.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import static org.assertj.core.api.Assertions.*;

/** S13 task evidence over curl/TCP, real JWT validation and a disposable Mongo replica set. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "shared.scheduling.enabled=false")
class LifecycleCurlIT extends BookingFixtures {
    @LocalServerPort int port;
    @Autowired JwtEncoder encoder;
    @Value("${identity.issuer}") String issuer;
    String token(String role) {
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("s08-" + (role.equals("ADMIN") ? "admin" : "laura"))
                .audience(List.of(role.equals("ADMIN") ? "clubs-admin" : "clubs-app"))
                .issuedAt(clock.instant()).expiresAt(clock.instant().plusSeconds(900)).claim("clubId", CLUB)
                .claim("memberId", role.equals("ADMIN") ? "s08-m-admin" : "s08-m-laura").claim("roles", List.of(role)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
    @Test void T_13_08_T_13_16_T_13_22_curlApprovalOverviewLeaveAndList() throws Exception {
        mongo.save(planDocument("s13-monthly", "MONTHLY"), "plans");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("s08-m-laura")), new Update().set("planId", "s13-monthly"), "members");
        var requested = curl("POST", "/api/v1/me/inactivity-periods", Map.of("fromMonth", "2026-11", "toMonth", "2026-12"), 201, "MEMBER");
        var approved = curl("POST", "/api/v1/inactivity-periods/"+requested.path("id").asText()+"/decision", Map.of("decision", "APPROVED"), 200, "ADMIN");
        assertThat(approved.at("/feeSnapshot/firstMonth/amountMinor").asLong()).isEqualTo(2000);
        var period = mongo.findById(requested.path("id").asText(), Document.class, "inactivity_periods");
        System.out.println("InactivityPeriod id=[truncated] state="+period.getString("state")+" feeSnapshot="+period.get("feeSnapshot")+" history="+period.get("history")+" cancelledBookings="+period.get("cancelledBookings"));
        var overview = curl("GET", "/api/v1/members/s08-m-laura/overview", null, 200, "ADMIN");
        assertThat(overview.at("/inactivity/upcoming/fromMonth").asText()).isEqualTo("2026-11");
        var leave = curl("POST", "/api/v1/me/leave-requests", Map.of("requestedDate", "2026-10-31", "reasonKey", "EXTERNAL"), 201, "MEMBER");
        curl("POST", "/api/v1/leave-requests/"+leave.path("id").asText()+"/decision", Map.of("decision", "APPROVED"), 200, "ADMIN");
        var list = curl("GET", "/api/v1/members?filter=displayStatus:eq:LEAVE_SCHEDULED", null, 200, "ADMIN");
        assertThat(list.path("items").toString()).contains("s08-m-laura");
        System.out.println("PASS S13 curl sequence: request -> approve -> overview -> request leave -> approve -> planned-leave list");
    }
    JsonNode curl(String method, String path, Object body, int expected, String role) throws Exception {
        Path input = Files.createTempFile("lifecycle-curl-", ".json"), output = Files.createTempFile("lifecycle-curl-", ".response");
        try {
            String config = "url = \"http://127.0.0.1:" + port + path + "\"\nheader = \"Host: " + HOST + "\"\n";
            config += "header = \"Authorization: Bearer " + token(role) + "\"\n";
            if (method.equals("POST")) { config += "header = \"Idempotency-Key: " + UUID.randomUUID() + "\"\n"; }
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
}

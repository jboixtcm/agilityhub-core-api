package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.census.api.SignupRequests;
import com.agilityhub.core.clubs.census.api.SignupResponses;
import com.agilityhub.core.clubs.common.api.DashboardContracts;
import com.agilityhub.core.payments.api.CheckoutContracts;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import static org.assertj.core.api.Assertions.*;

class E3ResponseContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test void T_04_29_T_04_33_responseFixturesRetainNestedFieldsEnumsMoneyAndUtcDates() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/contracts/e3-responses.json")) {
            var entries = mapper.readTree(input).fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                var type = Class.forName(SignupResponses.class.getName() + "$" + entry.getKey());
                var value = mapper.treeToValue(entry.getValue(), type);
                JSONAssert.assertEquals(entry.getKey(), entry.getValue().toString(), mapper.writeValueAsString(value), true);
            }
        }
        var checkout = new CheckoutContracts.CheckoutSession("https://checkout.example.test/session", "fictional-session");
        assertThat(mapper.valueToTree(checkout).fieldNames()).toIterable().containsExactly("checkoutUrl", "checkoutSessionId");
    }
    @Test void T_04_15_T_04_22_requestBindingsKeepS04NamesAndNeverSerializeBankOrCheckoutCapabilities() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/contracts/e3-signup-request.json")) {
            var request = mapper.readValue(input, SignupRequests.SignupRequest.class);
            assertThat(request.person().idDocument().value()).isEqualTo("12345678Z");
            assertThat(request.person().address().town()).isEqualTo("Example Town");
            assertThat(request.payment().iban()).isEqualTo("ES0000000000000000000000");
            assertThat(request.dog().birthMonth()).isEqualTo("2024-04");
            assertThat(request.consents().imageUse().granted()).isFalse();
            assertThat(mapper.writeValueAsString(request)).doesNotContain("ES0000000000000000000000");
        }
        var request = new CheckoutContracts.CheckoutSessionRequest("member-a", "fictional-secret-capability", "https://club.example.test/success", "https://club.example.test/cancel");
        assertThat(mapper.writeValueAsString(request)).doesNotContain("fictional-secret-capability");
        for (var type : List.of(SignupResponses.SignupMember.class, SignupResponses.IdentityCheckResult.class,
                SignupResponses.FamilyGroupLookupResult.class, SignupResponses.UpfrontLine.class, CheckoutContracts.CheckoutSession.class)) {
            assertThat(java.util.Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                    .doesNotContain("iban", "passwordHash", "clubId", "ipHash", "paymentIntentId");
        }
    }
    @Test void T_14_01_disabledDashboardBlocksSerializeAsExplicitNull() throws Exception {
        var value = new DashboardContracts.Dashboard(Instant.parse("2026-09-09T10:00:00Z"), LocalDate.of(2026,9,9),
                new DashboardContracts.DashboardWeek(LocalDate.of(2026,9,7),LocalDate.of(2026,9,13)),
                new DashboardContracts.DashboardKpis(null,null,null,null),null,null,null);
        var json = mapper.valueToTree(value);
        for (String field : List.of("riskReview", "pendingSignups", "dogsByLevel")) { assertThat(json.has(field)).isTrue(); assertThat(json.get(field).isNull()).isTrue(); }
        assertThat(json.get("kpis").size()).isEqualTo(4);
        json.get("kpis").forEach(block -> assertThat(block.isNull()).isTrue());
    }
    @Test void T_04_11_T_04_17_T_04_21_notificationFixturesUseApprovedEventsAndVariables() throws Exception {
        var catalog = java.nio.file.Files.readString(java.nio.file.Path.of("docs/specs/00-transversal/CATALEG_NOTIFICACIONS.md"));
        try (var input = getClass().getResourceAsStream("/fixtures/contracts/e3-notifications.json")) {
            var notifications = mapper.readTree(input);
            assertThat(notifications.fieldNames()).toIterable().containsExactly("N-01","N-02","N-03","N-37","N-39");
            notifications.fields().forEachRemaining(entry -> {
                var row = catalog.lines().filter(line -> line.startsWith("| " + entry.getKey() + " |")).findFirst().orElseThrow();
                assertThat(row).contains("`" + entry.getValue().path("event").asText() + "`");
                entry.getValue().path("variables").forEach(variable -> assertThat(catalog).contains(variable.asText()));
            });
        }
    }
}

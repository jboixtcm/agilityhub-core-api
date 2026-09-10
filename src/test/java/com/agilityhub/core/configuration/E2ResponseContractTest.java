package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.census.api.CensusRequests;
import com.agilityhub.core.clubs.census.api.CensusResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import static org.assertj.core.api.Assertions.assertThat;

class E2ResponseContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test void T_03_22_T_05_01_T_14_01_referenceMockResponsesKeepTheirWireFieldsMoneyAndUtcDates() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/contracts/e2-responses.json")) {
            var fixtures = mapper.readTree(input);
            var entries = fixtures.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                var type = Class.forName("com.agilityhub.core." + entry.getKey());
                var value = mapper.treeToValue(entry.getValue(), type);
                JSONAssert.assertEquals(entry.getKey(), entry.getValue().toString(), mapper.writeValueAsString(value), false);
            }
        }
    }

    @Test void T_03_09_T_03_33_memberProjectionsAndWriteBodiesExcludeSensitiveAndReadOnlyFields() throws Exception {
        var forbidden = Map.of(
                CensusResponses.MemberInstructorView.class, java.util.Set.of("paymentMethod", "nextInvoiceDate", "consents", "internalNotes", "bookingBlock"),
                CensusResponses.MeDog.class, java.util.Set.of("chip", "memberId", "remarks"),
                CensusResponses.PaymentMethodView.class, java.util.Set.of("iban", "stripeSetupIntentId", "holderTaxId"),
                CensusRequests.MeProfilePatch.class, java.util.Set.of("idDocument", "firstName", "paymentMethod", "planId", "roles"),
                CensusRequests.MemberPatch.class, java.util.Set.of("accountId", "memberNumber", "status", "planId", "priceId", "roles"));
        forbidden.forEach((type, fields) -> assertThat(java.util.Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .doesNotContainAnyElementsOf(fields));
        var input = new CensusRequests.SepaInput("fictional-iban", "Example Member", "fictional-tax-id");
        assertThat(mapper.writeValueAsString(input)).doesNotContain("fictional-iban");
        var inherited = mapper.readValue("{\"override\":null}", CensusRequests.FreeTrainingRequest.class);
        assertThat(inherited.override()).isNull();
        var result = new CensusResponses.FreeTraining(true, CensusResponses.FreeTrainingSource.LEVEL, null);
        assertThat(mapper.readTree(mapper.writeValueAsString(result)).path("override").isNull()).isTrue();
    }
}

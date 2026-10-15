package com.agilityhub.core.configuration;

import com.agilityhub.core.platform.api.PlatformClubContracts;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import static org.assertj.core.api.Assertions.*;

class E10ResponseContractTest {
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    JsonNode published() throws Exception { return mapper.readTree(Path.of(System.getProperty("e10.contract", "docs/openapi/openapi.json")).toFile()); }
    @Test void T_17_03_R_17_03_domainPublishesStableTxtChallengeInstructions() throws Exception {
        var schemas=published().path("components").path("schemas");
        var verification=schemas.path("PlatformDomain").path("properties").path("verification");
        assertThat(verification.toString()).contains("DomainVerificationChallenge");
        var challenge=schemas.path("DomainVerificationChallenge");
        assertThat(challenge.path("required")).contains(mapper.valueToTree("recordName"),mapper.valueToTree("recordValue"));
        assertThat(challenge.path("description").asText()).contains("_agilityhub-verify", "agh-verify-{token}", "stable until verified");
    }
    @Test void T_17_05_R_17_05_providerReadContractKeepsEditableNonSecretConfiguration() throws Exception {
        var properties=published().path("components").path("schemas").path("PlatformProviderSummary").path("properties");
        assertThat(properties.fieldNames()).toIterable().contains("creditorName","creditorId","suffix","ibanMasked","instructions","accountIdMasked")
                .doesNotContain("iban","secretKey","webhookSecret","accountId");
    }
    @Test void T_17_10_R_17_12_clubPagePublishesAppliedFiltersLikeListPage() throws Exception {
        var page=published().path("components").path("schemas").path("PlatformClubPage");
        assertThat(page.path("required")).contains(mapper.valueToTree("appliedFilters"));
        assertThat(page.path("properties").path("appliedFilters").path("items").path("$ref").asText()).isEqualTo("#/components/schemas/Filter");
    }
    @Test void T_17_12_R_17_12_platformLogoUploadReusesAttachmentResponseAndPurposes() throws Exception {
        var api=published();
        var operation=api.path("paths").path("/api/v1/platform/clubs/{clubId}/upload-url").path("post");
        assertThat(operation.path("responses").path("201").path("content").path("application/json").path("schema"))
                .isEqualTo(api.path("paths").path("/api/v1/attachments/upload-url").path("post").path("responses").path("201").path("content").path("application/json").path("schema"));
        assertThat(api.path("components").path("schemas").path("PlatformThemeUploadRequest").path("properties").path("purpose").path("enum").toString())
                .isEqualTo("[\"LOGO\",\"LOGO_DARK\",\"MARK\"]");
    }
    @Test void T_17_05_R_17_05_responseFixturesRoundTripNullsAndNeverExposeCredentials() throws Exception {
        var fixtures=mapper.readTree(Path.of("src/test/resources/fixtures/contracts/e10-responses.json").toFile());
        var schemas=published().path("components").path("schemas");
        for(var entry:fixtures.properties()) {
            Class<?> type=Class.forName(PlatformClubContracts.class.getName()+"$"+entry.getKey());
            assertThat(schemas.path(entry.getKey()).path("properties").fieldNames()).as(entry.getKey()).toIterable()
                    .containsExactlyInAnyOrderElementsOf(new ArrayList<>(mapper.convertValue(entry.getValue(),Map.class).keySet()));
            JSONAssert.assertEquals(entry.getKey(),entry.getValue().toString(),mapper.writeValueAsString(mapper.treeToValue(entry.getValue(),type)),true);
        }
        assertThat(fixtures.path("PlatformClub").toString()).doesNotContain("secretKey","webhookSecret","publicApiKeyHash","\"iban\"","memberId");
        var providers=fixtures.path("PlatformClub").path("paymentProviders");
        assertThat(providers.path("SEPA_XML").path("creditorName").asText()).isEqualTo("Example Club");
        assertThat(providers.path("SEPA_XML").path("ibanMasked").asText()).isEqualTo("****1332");
        assertThat(providers.path("MANUAL").path("instructions").size()).isEqualTo(3);
        for(String field:List.of("secretKey","webhookSecret","iban"))
            assertThat(schemas.path("PlatformProviderRequest").path("properties").path(field).path("writeOnly").asBoolean()).as(field).isTrue();
        var request=mapper.readValue("{\"version\":0,\"secretKey\":\"fictional-secret\",\"webhookSecret\":\"fictional-webhook\",\"iban\":\"fictional-iban\"}",PlatformClubContracts.PlatformProviderRequest.class);
        assertThat(request.secretKey()).isEqualTo("fictional-secret");
        assertThat(request.webhookSecret()).isEqualTo("fictional-webhook");
        assertThat(request.iban()).isEqualTo("fictional-iban");
        assertThat(mapper.valueToTree(request).toString()).doesNotContain("fictional-secret","fictional-webhook","fictional-iban");
        assertThat(request.toString()).doesNotContain("fictional-secret","fictional-webhook","fictional-iban");
    }
    @Test void T_17_06_R_17_06_checklistHasSevenModelFieldsAndUsageHasOnlyAggregates() throws Exception {
        var schemas=published().path("components").path("schemas");
        assertThat(schemas.path("OnboardingChecklist").path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("dns","sepa","branding","templates","faq","legal","firstAdmin");
        assertThat(schemas.path("PlatformClubUsage").path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("month","membersActive","smsSentMonth","storageBytes");
        assertThat(schemas.path("PlatformClubListItem").path("required").toString()).isEqualTo("[\"id\"]");
        assertThat(schemas.path("PlatformClubPatch").path("required").toString()).isEqualTo("[\"version\"]");
        assertThat(schemas.path("PlatformDomain").path("properties").path("status").path("enum").toString()).contains("BROKEN");
    }
    @Test void T_17_02_R_17_02_definitionIsTypedAndCanonicalErrorStatusesWin() throws Exception {
        var definition=published().path("components").path("schemas").path("ClubDefinition");
        assertThat(definition.path("properties").path("club").path("properties").has("timeZone")).isTrue();
        assertThat(definition.path("properties").has("accounts")).isFalse();
        assertThat(definition.toString()).doesNotContain("#/$defs/","bodyFile");
        Map.of(400,"VALIDATION_ERROR",403,"SUPPORT_RESTRICTED",404,"TEMPLATE_CLUB_NOT_FOUND,CLUB_NOT_FOUND",
                409,"SLUG_TAKEN,HOST_ALREADY_USED,PROVIDER_IN_USE,CATALOG_NOT_EMPTY,LAST_ADMIN,LAST_PLATFORM_ADMIN,MEMBERSHIP_EXISTS,STALE_VERSION,INVALID_STATE",
                422,"HOST_RESERVED,HOST_NOT_VERIFIED,THEME_CONTRAST,PROVIDER_CONFIG_INVALID,STRIPE_CONNECTION_FAILED,TIMEZONE_CHANGE_BLOCKED,MODULE_DEPENDENCY,CURRENCY_MISMATCH",501,"NOT_IMPLEMENTED")
                .forEach((status,codes)->{for(String code:codes.split(",")) assertThat(ErrorCode.valueOf(code).httpStatus()).as(code).isEqualTo(status);});
    }
}

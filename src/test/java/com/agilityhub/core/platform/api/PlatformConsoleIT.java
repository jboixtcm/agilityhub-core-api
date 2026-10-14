package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class PlatformConsoleIT extends AbstractIntegrationTest {
    static final String BASE="/api/v1/platform/clubs";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired MongoTemplate mongo;
    @BeforeEach void setup() {
        wipeDatabaseKeepingBootstrap();
        clubs.save(PlatformFixtures.club("console-target","console.example.test"));
        clubs.save(PlatformFixtures.club("console-source","source.example.test"));
    }
    MockHttpServletRequestBuilder actor(MockHttpServletRequestBuilder call) {
        return call.header("Host","unknown.example.test").with(jwt().jwt(j->j.subject("console-actor"))
                .authorities(new SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN")));
    }
    JsonNode call(MockHttpServletRequestBuilder call, Object body, int status) throws Exception {
        if(body!=null) call.contentType("application/json").content(mapper.writeValueAsString(body));
        var response=mvc.perform(actor(call)).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsString().isEmpty()?mapper.nullNode():mapper.readTree(response.getContentAsString());
    }
    long version() { return clubs.findById("console-target").orElseThrow().version(); }
    Map<String,Object> modules(Object selection) { return Map.of("version",version(),selection instanceof String?"preset":"modules",selection); }
    @Test void T_17_02_R_17_02_createIsUniqueAndAlwaysStartsOnboarding() throws Exception {
        var routes=mapper.readTree(java.nio.file.Path.of("src/test/resources/fixtures/contracts/e10-routes.json").toFile());
        var body=(ObjectNode)routes.get(1).path("body").deepCopy();
        body.withObject("club").put("status","ACTIVE");
        String key=UUID.randomUUID().toString();
        var created=call(post(BASE).header("Idempotency-Key",key),body,201);
        assertThat(call(post(BASE).header("Idempotency-Key",key),body,201)).isEqualTo(created);
        assertThat(created.path("club").path("status").asText()).isEqualTo("ONBOARDING");
        assertThat(call(post(BASE).header("Idempotency-Key",UUID.randomUUID()),body,409).path("code").asText()).isEqualTo("SLUG_TAKEN");
    }
    @Test void T_17_04_R_17_04_modulesValidateDependenciesAndInvalidateAfterCommit() throws Exception {
        var result=call(put(BASE+"/console-target/modules"),modules("MINIM"),200);
        assertThat(mapper.convertValue(result.path("modules"),Set.class)).containsExactlyInAnyOrder("WAITLIST","FAQ","PUSH");
        assertThat(call(put(BASE+"/console-target/modules"),modules(List.of("PACKS")),422).path("code").asText()).isEqualTo("MODULE_DEPENDENCY");
    }
    @Test void T_17_05_R_17_05_providerSecretsAreEncryptedAndNeverReturned() throws Exception {
        var result=call(put(BASE+"/console-target/payment-providers/STRIPE"),Map.of("version",version(),"enabled",true,
                "secretKey","sk_test_fictional_console","webhookSecret","whsec_fictional_console","publishableKey","pk_test_console1234","mode","test"),200);
        assertThat(result.path("configured").asBoolean()).isTrue();
        assertThat(result.path("last4").asText()).isEqualTo("1234");
        assertThat(mongo.findById("console-target",org.bson.Document.class,"clubs").toJson()).doesNotContain("sk_test_","whsec_fictional");
        assertThat(call(get(BASE+"/console-target"),null,200).toString()).doesNotContain("secretKey","webhookSecret","pk_test_console1234");
    }
    @Test void T_17_06_R_17_06_checklistUsesCurrentConfiguration() throws Exception {
        var result=call(get(BASE+"/console-target/checklist"),null,200);
        assertThat(result.size()).isEqualTo(7);
        assertThat(result.path("dns").asBoolean()).isFalse();
        assertThat(result.path("firstAdmin").asBoolean()).isFalse();
    }
    @Test void T_17_13_R_17_07_statusTransitionIsManualAndAudited() throws Exception {
        call(put(BASE+"/console-target/status").header("Idempotency-Key",UUID.randomUUID()),Map.of("status","SUSPENDED","reason","Maintenance"),200);
        assertThat(clubs.findById("console-target").orElseThrow().status()).isEqualTo(Club.Status.SUSPENDED);
        assertThat(call(put(BASE+"/console-target/status").header("Idempotency-Key",UUID.randomUUID()),Map.of("status","ONBOARDING"),409).path("code").asText()).isEqualTo("INVALID_STATE");
        call(put(BASE+"/console-target/status").header("Idempotency-Key",UUID.randomUUID()),Map.of("status","ACTIVE"),200);
    }
    @Test void T_17_07_R_17_08_cloneEmptyPartsWithoutCopyingOperationalData() throws Exception {
        var result=call(post(BASE+"/console-target/catalog-clone").header("Idempotency-Key",UUID.randomUUID()),Map.of("fromClubId","console-source","parts",List.of("RINGS")),200);
        assertThat(result.path("copied").path("RINGS").asInt(-1)).isZero();
    }
    @Test void T_17_08_R_17_09_invitationCreatesMembershipAndProtectsLastAdmin() throws Exception {
        onboarding("console-target");
        var result=call(post(BASE+"/console-target/admins").header("Idempotency-Key",UUID.randomUUID()),Map.of("email","invited@example.test","name","Fictional Admin"),201);
        assertThat(result.path("membershipStatus").asText()).isEqualTo("ACTIVE");
        assertThat(call(delete(BASE+"/console-target/admins/"+result.path("accountId").asText()).header("Idempotency-Key",UUID.randomUUID()),null,409).path("code").asText()).isEqualTo("LAST_ADMIN");
    }
    @Test void T_17_10_R_17_12_listContainsConfigurationAndAggregatesOnly() throws Exception {
        var result=call(get(BASE).param("fields","name,usage").param("filter","template:eq:false"),null,200);
        assertThat(result.path("totalItems").asInt()).isEqualTo(2);
        assertThat(result.path("items").get(0).propertyStream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("id","name","usage");
    }
    @Test void T_17_14_R_17_13_targetLocaleAndOptimisticVersionAreEnforced() throws Exception {
        long original=version();
        var result=call(patch(BASE+"/console-target"),Map.of("version",original,"locales",List.of("en"),"defaultLocale","en"),200);
        assertThat(result.path("defaultLocale").asText()).isEqualTo("en");
        assertThat(call(patch(BASE+"/console-target"),Map.of("version",original,"name","Stale"),409).path("code").asText()).isEqualTo("STALE_VERSION");
        assertThat(call(patch(BASE+"/console-target"),Map.of("version",version(),"defaultLocale","ca"),400).path("code").asText()).isEqualTo("VALIDATION_ERROR");
    }
    @Autowired com.agilityhub.core.platform.application.ClubConfigService configs;
    @Autowired com.agilityhub.core.platform.application.HostTenantResolver hosts;
    @Autowired com.agilityhub.core.shared.application.OutboxDispatcher outbox;
    @Autowired com.agilityhub.core.platform.application.ClubTemplateProvisioner templates;
    @Autowired com.agilityhub.core.platform.application.BillingProviderSettings billingSettings;
    @Autowired com.agilityhub.core.payments.application.StripeWebhooks webhooks;
    @Autowired com.agilityhub.core.clubs.followup.application.AttachmentStorage storage;
    @Autowired com.agilityhub.core.identity.application.AccountService accounts;
    @Autowired com.agilityhub.core.identity.application.MembershipService memberships;
    @Autowired com.agilityhub.core.identity.application.PasswordHasher passwords;
    void raw(String collection,org.bson.Document value) { mongo.insert(value,collection); }
    org.bson.Document row(String id,String club) {return new org.bson.Document("_id",id).append("clubId",club);}
    void onboarding(String id) {
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
                new org.springframework.data.mongodb.core.query.Update().set("status","ONBOARDING"),Club.class);
        configs.invalidate(id);
    }
    @Test void T_17_04_R_17_04_disablePreservesBookingsAndImmediateBrandingUsesNewModules() throws Exception {
        configs.get("console-target");hosts.invalidate();
        raw("training_bookings",row("existing-training","console-target").append("status","CONFIRMED"));
        call(put(BASE+"/console-target/modules"),modules("MINIM"),200);
        assertThat(configs.get("console-target").modules()).doesNotContain(com.agilityhub.core.platform.application.Module.FREE_TRAINING);
        assertThat(mongo.findById("existing-training",org.bson.Document.class,"training_bookings")).isNotNull();
        var response=mvc.perform(get("/api/v1/training-slots").header("Host","console.example.test").with(jwt().jwt(j->j.subject("reader").claim("clubId","console-target"))
                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(404);assertThat(mapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("MODULE_DISABLED");
    }
    @Test void T_17_05_R_17_05_omittedSecretsPreserveCiphertextAndModeSwitchRequiresReplacement() throws Exception {
        call(put(BASE+"/console-target/payment-providers/STRIPE"),Map.of("version",version(),"enabled",true,"secretKey","sk_test_fictional_console",
                "webhookSecret","whsec_fictional_console","publishableKey","pk_test_console1234","mode","test"),200);
        var before=clubs.findById("console-target").orElseThrow().paymentProviders().get("STRIPE");
        call(put(BASE+"/console-target/payment-providers/STRIPE"),Map.of("version",version(),"publishableKey","pk_test_console4321","mode","test"),200);
        var after=(Map<?,?>)clubs.findById("console-target").orElseThrow().paymentProviders().get("STRIPE");
        assertThat(after.get("secretKeyEnc")).isEqualTo(((Map<?,?>)before).get("secretKeyEnc"));
        assertThat(call(put(BASE+"/console-target/payment-providers/STRIPE"),Map.of("version",version(),"publishableKey","pk_live_console4321","mode","live"),422).path("code").asText()).isEqualTo("PROVIDER_CONFIG_INVALID");
    }
    @Test void T_17_05_R_17_05_sepaIsValidatedEncryptedUsableAndCannotRemoveUsedProvider() throws Exception {
        var config=new LinkedHashMap<String,Object>(Map.of("version",version(),"enabled",true,"creditorName","Example Association","creditorId","ES97000G12345678",
                "iban","ES9121000418450200051332","suffix","000"));
        call(put(BASE+"/console-target/payment-providers/SEPA_XML"),config,200);
        assertThat(clubs.findById("console-target").orElseThrow().paymentProviders().toString()).doesNotContain("ES9121000418450200051332");
        try(var scope=com.agilityhub.core.shared.application.TenantContext.open("console-target")) {
            assertThat(billingSettings.sepaCreditor().orElseThrow().iban()).isEqualTo("ES9121000418450200051332");
        }
        for(int n=0;n<3;n++) raw("members",row("payer-"+n,"console-target").append("status","ACTIVE").append("paymentMethod",new org.bson.Document("type","SEPA_DD")));
        var error=call(delete(BASE+"/console-target/payment-providers/SEPA_XML").param("version",Long.toString(version())),null,409);
        assertThat(error.path("details").path("count").asInt()).isEqualTo(3);
        config.put("version",version());config.put("iban","ES0000000000000000000000");
        assertThat(call(put(BASE+"/console-target/payment-providers/SEPA_XML"),config,422).path("code").asText()).isEqualTo("PROVIDER_CONFIG_INVALID");
    }
    @Test void T_17_05_R_17_05_stripeProbeRecordsResultAndExportsOnlyPlaceholders() throws Exception {
        call(put(BASE+"/console-target/payment-providers/STRIPE"),Map.of("version",version(),"enabled",true,"secretKey","sk_test_fictional_console",
                "webhookSecret","whsec_fictional_console","publishableKey","pk_test_console1234","mode","test"),200);
        assertThat(call(post(BASE+"/console-target/payment-providers/stripe/test"),null,200).path("ok").asBoolean()).isTrue();
        assertThat(call(get(BASE+"/console-target"),null,200).path("paymentProviders").path("STRIPE").path("lastTestAt").isTextual()).isTrue();
        var response=mvc.perform(actor(get(BASE+"/console-target/definition").accept("application/yaml"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("env:").doesNotContain("sk_test_fictional_console","whsec_fictional_console","secretKeyEnc","ibanEnc","accounts:");
    }
    @Test void T_17_06_R_17_06_eachChecklistCellUsesTheRelevantCurrentFact() throws Exception {
        var c=clubs.findById("console-target").orElseThrow();var tree=(ObjectNode)mapper.valueToTree(c);
        tree.putArray("domains").add(mapper.valueToTree(c.domains().getFirst()));
        tree.withObject("theme").put("logoUrl","https://example.test/logo.png");
        tree.withObject("legal").put("privacyPolicyUrl","https://example.test/privacy");
        tree.putArray("modules").add("FAQ").add("BILLING");
        tree.putObject("paymentProviders").putObject("MANUAL").put("enabled",true).putObject("instructions").put("ca","Pay at reception");
        clubs.save(mapper.treeToValue(tree,Club.class));
        raw("faq_entries",row("faq-one","console-target"));
        raw("memberships",row("admin-one","console-target").append("accountId","admin-one").append("roles",List.of("ADMIN"))
                .append("status","ACTIVE").append("lastAccessAt",Date.from(clock.instant())));
        try(var scope=com.agilityhub.core.shared.application.TenantContext.open("console-target")) {
            templates.provision(com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed.eligible().stream().map(spec->spec.code()).toList(),List.of("ca","es"),"ca");
        }
        var result=call(get(BASE+"/console-target/checklist"),null,200);
        for(String field:List.of("dns","sepa","branding","templates","faq","legal","firstAdmin")) assertThat(result.path(field).asBoolean()).as(field).isTrue();
        assertThat(clubs.findById("console-target").orElseThrow().status()).isEqualTo(c.status());
    }
    @Test void T_17_13_R_17_07_suspensionRejectsLoginAndQueuesStripeUntilReactivation() throws Exception {
        configs.invalidate("console-target");hosts.invalidate();
        try(var scope=com.agilityhub.core.shared.application.TenantContext.open("console-target")) {
            var account=accounts.getOrCreate("login@example.test","Example Admin","ca",com.agilityhub.core.identity.persistence.Account.Source.CONSOLE,passwords.hash("Example password 123!"),false);
            memberships.setRoles(account.id(),Set.of(com.agilityhub.core.identity.domain.Role.ADMIN));
        }
        call(put(BASE+"/console-target/status").header("Idempotency-Key",UUID.randomUUID()),Map.of("status","SUSPENDED","reason","Maintenance"),200);
        var login=mvc.perform(post("/oauth2/token").header("Host","console.example.test").contentType("application/x-www-form-urlencoded")
                .param("grant_type","password").param("client_id","clubs-admin").param("username","login@example.test").param("password","Example password 123!")).andReturn().getResponse();
        assertThat(login.getStatus()).isEqualTo(403);assertThat(mapper.readTree(login.getContentAsString()).path("code").asText()).isEqualTo("CLUB_SUSPENDED");
        byte[] payload=mapper.writeValueAsBytes(Map.of("id","evt_console_paused","type","unhandled.example","created",clock.instant().getEpochSecond(),"data",Map.of("object",Map.of("id","example"))));
        webhooks.receive("console-target",payload);
        var row=mongo.findById("evt_console_paused",org.bson.Document.class,"stripe_events");assertThat(row.get("processedAt")).isNull();
        call(put(BASE+"/console-target/status").header("Idempotency-Key",UUID.randomUUID()),Map.of("status","ACTIVE"),200);
        try(var scope=com.agilityhub.core.shared.application.TenantContext.open("console-target")) {webhooks.process("evt_console_paused");}
        assertThat(mongo.findById("evt_console_paused",org.bson.Document.class,"stripe_events").get("processedAt")).isNotNull();
    }
    @Test void T_17_08_R_17_09_invitationDeliversN53OnceAndDoesNotCreateAMember() throws Exception {
        onboarding("console-target");
        String key=UUID.randomUUID().toString();var input=Map.of("email","invited@example.test","name","Fictional Admin");
        var one=call(post(BASE+"/console-target/admins").header("Idempotency-Key",key),input,201);
        var two=call(post(BASE+"/console-target/admins").header("Idempotency-Key",key),input,201);assertThat(two).isEqualTo(one);
        outbox.dispatch();outbox.dispatch();
        var notices=mongo.find(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("code").is("N-53")),org.bson.Document.class,"notifications");
        assertThat(notices).hasSize(1);assertThat(notices.getFirst().getString("status")).isIn("SENT","DELIVERED");
        assertThat(notices.getFirst().toJson()).doesNotContain("?t=");
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(),"members")).isZero();
        var member=mongo.findOne(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("accountId").is(one.path("accountId").asText())),org.bson.Document.class,"memberships");
        assertThat(member.get("memberId")).isNull();
        assertThat(call(post(BASE+"/console-target/admins").header("Idempotency-Key",UUID.randomUUID()),input,409).path("code").asText()).isEqualTo("MEMBERSHIP_EXISTS");
    }
    @Test void T_17_08_R_17_09_removalSuspendsOnlyTheTargetClub() throws Exception {
        onboarding("console-target");onboarding("console-source");
        var one=call(post(BASE+"/console-target/admins").header("Idempotency-Key",UUID.randomUUID()),Map.of("email","one@example.test","name","First"),201);
        call(post(BASE+"/console-target/admins").header("Idempotency-Key",UUID.randomUUID()),Map.of("email","two@example.test","name","Second"),201);
        call(post(BASE+"/console-source/admins").header("Idempotency-Key",UUID.randomUUID()),Map.of("email","one@example.test","name","First"),201);
        call(delete(BASE+"/console-target/admins/"+one.path("accountId").asText()).header("Idempotency-Key",UUID.randomUUID()),null,204);
        assertThat(call(get(BASE+"/console-source/admins"),null,200).get(0).path("membershipStatus").asText()).isEqualTo("ACTIVE");
    }
    @Test void T_17_10_R_17_12_platformAuditExcludesMemberChangesEvenWhenFiltered() throws Exception {
        call(patch(BASE+"/console-target"),Map.of("version",version(),"name","Updated organization"),200);
        raw("audit_entries",row("private-audit","console-target").append("action","MEMBER_UPDATED").append("entityType","Member").append("memberId","private-member").append("at",Date.from(clock.instant())).append("reason","Private member information"));
        var result=call(get("/api/v1/platform/audit-entries").param("filter","clubId:eq:console-target"),null,200);
        assertThat(result.path("totalItems").asInt()).isPositive();assertThat(result.toString()).doesNotContain("private-member","Private member information","MEMBER_UPDATED");
        assertThat(call(get("/api/v1/platform/audit-entries").param("filter","memberId:eq:private-member"),null,200).path("totalItems").asInt()).isZero();
    }
    @Test void T_17_14_R_17_13_manualInstructionsRequireTheTargetDefaultLocale() throws Exception {
        assertThat(call(put(BASE+"/console-target/payment-providers/MANUAL"),Map.of("version",version(),"instructions",Map.of("en","English")),422).path("code").asText()).isEqualTo("PROVIDER_CONFIG_INVALID");
        call(put(BASE+"/console-target/payment-providers/MANUAL"),Map.of("version",version(),"enabled",true,"instructions",Map.of("ca","Recepció")),200);
        var removed=call(delete(BASE+"/console-target/payment-providers/MANUAL").param("version",Long.toString(version())),null,200);
        assertThat(removed.path("configured").asBoolean()).isFalse();assertThat(removed.path("masked").isNull()).isTrue();
    }
    @Test void T_17_02_R_17_02_logoUploadIsScopedPublishedAndReplacedWithStablePublicUrls() throws Exception {
        var uploaded=call(post(BASE+"/console-target/theme/upload-url").header("Idempotency-Key",UUID.randomUUID()),Map.of("fileName","logo.png","mimeType","image/png","sizeBytes",3),201);
        String key=uploaded.path("fileKey").asText();
        storage.put(key,"image/png",3,new java.io.ByteArrayInputStream(new byte[]{1,2,3}));
        Map<String,Object> theme=mapper.convertValue(clubs.findById("console-target").orElseThrow().theme(),new com.fasterxml.jackson.core.type.TypeReference<>(){});
        theme.keySet().removeAll(Set.of("logoUrl","logoDarkUrl","markUrl"));theme.put("version",version());theme.put("logoFileKey",key);
        var result=call(put(BASE+"/console-target/theme"),theme,200);String path=result.path("logoUrl").asText();
        assertThat(path).isEqualTo("/api/v1/public/console-target/theme/"+key);
        assertThat(mvc.perform(get(path).header("Host","unknown.example.test")).andReturn().getResponse().getStatus()).isEqualTo(302);
        assertThat(mvc.perform(get(path.replace("console-target","console-source")).header("Host","unknown.example.test")).andReturn().getResponse().getStatus()).isEqualTo(404);
        theme.put("version",version());theme.put("logoFileKey","");call(put(BASE+"/console-target/theme"),theme,200);
        assertThat(mvc.perform(get(path).header("Host","unknown.example.test")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Autowired com.agilityhub.core.platform.application.ClubCatalogProvisioner catalogs;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Test void T_17_07_R_17_08_cloneCopiesCurrentPricesLocalizedTextAndStripsGeometry() throws Exception {
        try(var scope=com.agilityhub.core.shared.application.TenantContext.open("console-source")) {
            var input=mapper.readTree("""
                {"levels":[{"code":"BEGINNER","name":{"ca":"Iniciació","es":"Inicio"},"order":10}],
                 "rings":[{"name":"Example ring","shortName":"R1","color":"#3155A4","allowsFreeTraining":false}],
                 "plans":[{"code":"MONTHLY","name":{"ca":"Mensual","es":"Mensual"},"type":"MONTHLY"}],
                 "prices":[{"planCode":"MONTHLY","concept":"MONTHLY_FEE","amount":{"amountMinor":3000,"currency":"EUR"},"taxPercent":21,"validFrom":"2020-01-01"}],
                 "faq":[{"code":"ARRIVAL","category":{"ca":"General"},"question":{"ca":"Quan arribar?"},"answer":{"ca":"Deu minuts abans."}}]}
                """);
            transactions.executeWithoutResult(tx->catalogs.provision(catalogs.plan(input)));
            transactions.executeWithoutResult(tx->templates.provision(List.of("N-01"),List.of("ca","es"),"ca"));
            mongo.updateMulti(new org.springframework.data.mongodb.core.query.Query(),new org.springframework.data.mongodb.core.query.Update().set("activeSetupId","source-setup"),"rings");
        }
        var original=mongo.findOne(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is("console-source")),org.bson.Document.class,"prices");
        var expired=new org.bson.Document(original);expired.put("_id","expired-source-price");expired.put("validFrom","2010-01-01");expired.put("validTo","2019-12-31");raw("prices",expired);
        var future=new org.bson.Document(original);future.put("_id","future-source-price");future.put("validFrom","2040-01-01");raw("prices",future);
        call(patch(BASE+"/console-target"),Map.of("version",version(),"locales",List.of("en"),"defaultLocale","en"),200);
        var parts=List.of("LEVELS","RINGS","PLANS","FAQ","MESSAGE_TEMPLATES");
        var result=call(post(BASE+"/console-target/catalog-clone"),Map.of("fromClubId","console-source","parts",parts),200);
        for(String part:parts) assertThat(result.path("copied").path(part).asInt()).as(part).isEqualTo(1);
        assertThat(result.path("warnings").toString()).contains("omitted locale es","copied ca into en");
        try(var scope=com.agilityhub.core.shared.application.TenantContext.open("console-target")) {
            var exported=mapper.valueToTree(catalogs.export());
            assertThat(exported.path("levels").get(0).path("name")).isEqualTo(mapper.valueToTree(Map.of("en","Iniciació")));
            assertThat(exported.path("rings").get(0).hasNonNull("activeSetupId")).isFalse();
            assertThat(exported.path("prices").size()).isEqualTo(1);
            assertThat(mapper.valueToTree(templates.cloneSource()).get(0).path("body").has("en")).isTrue();
        }
        assertThat(call(post(BASE+"/console-target/catalog-clone"),Map.of("fromClubId","console-source","parts",parts),409).path("code").asText()).isEqualTo("CATALOG_NOT_EMPTY");
        call(patch(BASE+"/console-target"),Map.of("version",version(),"currency","GBP"),200);
        assertThat(call(post(BASE+"/console-target/catalog-clone"),Map.of("fromClubId","console-source","parts",List.of("PLANS")),422).path("code").asText()).isEqualTo("CURRENCY_MISMATCH");
    }
    @Test void T_17_02_R_17_02_createRollsBackCatalogFailureWithoutIdempotencyHeader() throws Exception {
        var routes=mapper.readTree(java.nio.file.Path.of("src/test/resources/fixtures/contracts/e10-routes.json").toFile());
        var body=(ObjectNode)routes.get(1).path("body").deepCopy();body.withObject("club").put("currency","GBP");
        body.withObject("catalogs").put("fromTemplate","console-source");
        assertThat(call(post(BASE),body,422).path("code").asText()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(clubs.findBySlug(body.path("club").path("slug").asText())).isEmpty();
    }
    @Test void T_17_04_R_17_04_allPresetsAndVersionConflictsMatchTheCatalog() throws Exception {
        long old=version();call(put(BASE+"/console-target/modules"),modules("COMPLET"),200);
        assertThat(clubs.findById("console-target").orElseThrow().modules()).doesNotContain(com.agilityhub.core.platform.application.Module.STATS,com.agilityhub.core.platform.application.Module.SOCIAL_LEAGUE);
        assertThat(call(put(BASE+"/console-target/modules"),Map.of("version",old,"preset","MINIM"),409).path("code").asText()).isEqualTo("STALE_VERSION");
        call(put(BASE+"/console-target/modules"),modules("CANIC"),200);
        assertThat(clubs.findById("console-target").orElseThrow().modules()).isEqualTo(com.agilityhub.core.platform.application.Module.defaults(com.agilityhub.core.platform.application.Module.Preset.CANIC));
        outbox.dispatch();
        assertThat(mongo.count(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("status").ne("PUBLISHED")),"domain_events")).isZero();
    }
    @Test void T_17_02_R_17_02_patchCannotChangeSlugOrTimezoneAfterClasses() throws Exception {
        assertThat(call(patch(BASE+"/console-target"),Map.of("version",version(),"slug","changed"),400).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        raw("class_sessions",row("scheduled","console-target"));
        assertThat(call(patch(BASE+"/console-target"),Map.of("version",version(),"timeZone","Europe/London"),422).path("code").asText()).isEqualTo("TIMEZONE_CHANGE_BLOCKED");
    }
    @Test void T_17_10_R_17_12_logoEndpointRejectsClubRolesAndCrossTenantUploads() throws Exception {
        for(String role:List.of("MEMBER","INSTRUCTOR","ADMIN")) {
            var response=mvc.perform(post(BASE+"/console-target/theme/upload-url").contentType("application/json")
                    .content("{\"fileName\":\"logo.png\",\"mimeType\":\"image/png\",\"sizeBytes\":3}")
                    .with(jwt().jwt(j->j.subject("club-actor").claim("clubId","console-target")).authorities(new SimpleGrantedAuthority("ROLE_"+role)))).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(403);
        }
        var uploaded=call(post(BASE+"/console-source/theme/upload-url"),Map.of("fileName","logo.png","mimeType","image/png","sizeBytes",3),201);
        Map<String,Object> theme=mapper.convertValue(clubs.findById("console-target").orElseThrow().theme(),new com.fasterxml.jackson.core.type.TypeReference<>(){});
        theme.keySet().removeAll(Set.of("logoUrl","logoDarkUrl","markUrl"));theme.put("version",version());theme.put("logoFileKey",uploaded.path("fileKey").asText());
        assertThat(call(put(BASE+"/console-target/theme"),theme,404).path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test void T_17_10_R_17_12_platformCanEditPlatformOnlyParametersInTheExplicitTarget() throws Exception {
        configs.get("console-target");configs.get("console-source");
        var result=call(put(BASE+"/console-target/parameters/auth.sessionDays"),Map.of("version",0,"value",45,"reason","Console policy"),200);
        assertThat(result.path("value").asInt()).isEqualTo(45);
        assertThat(configs.get("console-target").get("auth.sessionDays",Integer.class)).isEqualTo(45);
        assertThat(configs.get("console-source").get("auth.sessionDays",Integer.class)).isEqualTo(30);
        assertThat(call(put(BASE+"/console-target/parameters/auth.sessionDays"),Map.of("version",0,"value",50),409).path("code").asText()).isEqualTo("STALE_VERSION");
        assertThat(call(put(BASE+"/console-target/parameters/auth.sessionDays"),Map.of("version",1,"value",-1),400).path("code").asText()).isEqualTo("PARAMETER_INVALID");
    }
    @Test void T_17_02_R_17_02_createRejectsTakenAndReservedHosts() throws Exception {
        var routes=mapper.readTree(java.nio.file.Path.of("src/test/resources/fixtures/contracts/e10-routes.json").toFile());
        var body=(ObjectNode)routes.get(1).path("body").deepCopy();
        body.putArray("domains").addObject().put("host","console.example.test").put("app","clubs").put("primary",true);
        assertThat(call(post(BASE),body,409).path("code").asText()).isEqualTo("HOST_ALREADY_USED");
        ((ObjectNode)body.path("domains").get(0)).put("host","core.agilitydoghub.com");
        assertThat(call(post(BASE),body,422).path("code").asText()).isEqualTo("HOST_RESERVED");
    }

    @Test void T_17_08_R_17_09_memberlessInvitationsAreLimitedToOnboarding() throws Exception {
        long count=mongo.count(new org.springframework.data.mongodb.core.query.Query(),"accounts");
        var error=call(post(BASE+"/console-target/admins"),Map.of("email","new@example.test","name","Example Admin"),409);
        assertThat(error.path("code").asText()).isEqualTo("INVALID_STATE");
        assertThat(error.path("details").path("field").asText()).isEqualTo("memberId");
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(),"accounts")).isEqualTo(count);
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(),"memberships")).isZero();
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(),"domain_events")).isZero();
    }

    @Test void T_17_08_R_17_09_existingMemberCanBecomeAdminInAnActiveClub() throws Exception {
        String accountId;
        try(var scope=com.agilityhub.core.shared.application.TenantContext.open("console-target")) {
            var account=accounts.getOrCreate("member@example.test","Example Member","ca",com.agilityhub.core.identity.persistence.Account.Source.CONSOLE);
            accountId=account.id();memberships.setRoles(accountId,Set.of(com.agilityhub.core.identity.domain.Role.MEMBER));
        }
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is("console-target").and("accountId").is(accountId)),
                new org.springframework.data.mongodb.core.query.Update().set("memberId","existing-member"),"memberships");
        var invited=call(post(BASE+"/console-target/admins"),Map.of("email","member@example.test","name","Example Member"),201);
        assertThat(invited.path("accountId").asText()).isEqualTo(accountId);
        var membership=mongo.findOne(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("accountId").is(accountId)),org.bson.Document.class,"memberships");
        assertThat(membership.getString("memberId")).isEqualTo("existing-member");
        assertThat(membership.getList("roles",String.class)).containsExactlyInAnyOrder("MEMBER","ADMIN");
    }

    @Test void T_17_05_R_17_05_providerChangesPublishTheCatalogEventWithoutValues() throws Exception {
        call(put(BASE+"/console-target/payment-providers/MANUAL"),Map.of("version",version(),"enabled",true,"instructions",Map.of("ca","Private instructions")),200);
        call(delete(BASE+"/console-target/payment-providers/MANUAL").param("version",Long.toString(version())),null,200);
        var events=mongo.find(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("type").is("ClubPaymentProviderChanged")),org.bson.Document.class,"domain_events");
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(event->assertThat(event.get("payload",org.bson.Document.class)).containsEntry("clubId","console-target").containsEntry("provider","MANUAL"));
        assertThat(events).extracting(event->event.get("payload",org.bson.Document.class).getBoolean("configured")).containsExactlyInAnyOrder(true,false);
        assertThat(events.toString()).doesNotContain("Private instructions");
    }

    @Test void T_17_02_R_17_02_creationProvisionsTheConsoleDefinitionAtomically() throws Exception {
        var routes=mapper.readTree(java.nio.file.Path.of("src/test/resources/fixtures/contracts/e10-routes.json").toFile());
        var body=(ObjectNode)routes.get(1).path("body").deepCopy();
        body.remove("modules");body.put("preset","COMPLET");
        body.putObject("paymentProviders").putObject("MANUAL").put("enabled",true).putObject("instructions").put("ca","Pay on arrival");
        body.putObject("parameters").put("auth.sessionDays",45);
        body.putArray("admins").addObject().put("email","provisioned@example.test").put("name","Example Administrator").put("locale","ca");
        body.putObject("catalogs").putArray("levels").addObject().put("code","BEGINNER").put("order",10)
                .put("active",true).put("color","#123456").put("capacity",6).put("grantsFreeTraining",false).putObject("name").put("ca","Iniciació");
        body.putArray("messageTemplates").addObject().put("code","N-01");
        var created=call(post(BASE).header("Idempotency-Key",UUID.randomUUID()),body,201).path("club");
        String id=created.path("id").asText();
        assertThat(created.path("status").asText()).isEqualTo("ONBOARDING");
        assertThat(created.path("paymentProviders").path("MANUAL").path("configured").asBoolean()).isTrue();
        assertThat(configs.get(id).get("auth.sessionDays",Integer.class)).isEqualTo(45);
        assertThat(call(get(BASE+"/"+id+"/admins"),null,200).get(0).path("email").asText()).isEqualTo("provisioned@example.test");
        assertThat(mongo.count(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is(id)),"levels")).isEqualTo(1);
        var events=mongo.find(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is(id)),org.bson.Document.class,"domain_events");
        assertThat(events).extracting(e->e.getString("type")).contains("ClubCreated","ClubPaymentProviderChanged","ParameterChanged","MembershipChanged");
        outbox.dispatch();outbox.dispatch();
        var notices=mongo.find(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is(id).and("code").is("N-53")),org.bson.Document.class,"notifications");
        assertThat(notices).hasSize(1);
        assertThat(notices.getFirst().getString("status")).isIn("SENT","DELIVERED");
    }

}

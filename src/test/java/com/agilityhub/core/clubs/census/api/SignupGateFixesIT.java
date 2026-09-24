package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.definition.*;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static com.agilityhub.core.clubs.catalogs.domain.OfferTerms.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E3-T08: the api fixes of the gate E3 audit (`roadmap/reviews/gate-E3/consolidated.md`, M5–M12, M20, M21). Same Spring
 * context as {@link SignupIT}. Two clubs: the fixture club of {@code SignupIT} (one 60 € monthly plan) and a copy of the
 * Cànic seed (`seeds/club-canic.yaml`, with its hidden family fare `ABONAT_FAMILIAR`).
 */
@org.springframework.boot.test.context.SpringBootTest(properties={"shared.scheduling.enabled=false","core.security.rate-limits.enabled=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class SignupGateFixesIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired MongoTemplate mongo;@Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;@Autowired HostTenantResolver hosts;@Autowired ClubDefinitions definitions;@Autowired ClubDefinitionCodec codec;
    @Autowired com.agilityhub.core.clubs.census.application.SignupService signupService;
    @Autowired com.agilityhub.core.shared.application.OutboxDispatcher dispatcher;
    @Autowired com.agilityhub.core.clubs.messaging.application.EmailSender sender;
    @Autowired com.agilityhub.core.payments.application.FakeCheckoutGateway fake;
    String club,host,plan,level;
    int sequence;
    @BeforeEach void fixtureClub() {
        club="gt-"+UUID.randomUUID();host=club+".example.test";plan=UUID.randomUUID().toString();level=UUID.randomUUID().toString();
        ObjectNode tree=mapper.valueToTree(PlatformFixtures.club(club,host));tree.set("modules",mapper.valueToTree(Module.values()));
        tree.set("paymentProviders",mapper.valueToTree(Map.of("MANUAL",Map.of(),"SEPA_XML",Map.of())));
        clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(club);hosts.invalidate();
        var text=new LocalizedText(Map.of("ca","Example","es","Example","en","Example"),"en");
        mongo.insert(new Plan(plan,club,"MONTHLY",text,PlanType.MONTHLY,BillingMode.MONTHLY_FEE,1,new EntryFee(EntryFeeMode.STANDARD,null,null),null,null,text,null,true,true,0,true,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Price(UUID.randomUUID().toString(),club,plan,PriceConcept.MONTHLY_FEE,new Money(6000,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2020,1,1),null,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Document("_id",level).append("clubId",club).append("active",true).append("name",Map.of("en","Beginner")).append("order",0),"levels");
    }
    /** A fresh copy of the Cànic seed, active, on its own host; the fixture club stays unused. */
    void canic() {
        var input=codec.read(Path.of("seeds/club-canic.yaml"));input.remove("accounts");
        String slug="canic-"+UUID.randomUUID().toString().substring(0,8);
        ((ObjectNode)input.get("club")).put("slug",slug).put("status","ACTIVE");
        ((ObjectNode)input.at("/domains/0")).put("host",slug+".example.test");
        ((ObjectNode)input.at("/domains/1")).put("host","admin."+slug+".example.test");
        club=definitions.apply(input,false).id();host=slug+".example.test";hosts.invalidate();
        level=mongo.getCollection("levels").find(new Document("clubId",club).append("code","A")).first().getString("_id");
    }
    String planId(String code) { return mongo.getCollection("plans").find(new Document("clubId",club).append("code",code)).first().getString("_id"); }
    ObjectNode request(String planId) {
        int value=13000000+(++sequence);String national=String.format("%08d",value)+"TRWAGMYFPDXBNJZSQVHLCKE".charAt(value%23);
        return mapper.valueToTree(Map.of("locale","ca","website","","person",Map.of("idDocument",Map.of("type","DNI","value",national),"firstName","Example","lastName1","Applicant"+sequence,"birthDate","2000-01-01","gender","FEMALE","emails",List.of("gate"+sequence+"@example.test"),"phones",List.of(Map.of("prefix","+34","number","600000001")),"address",Map.of("street","Example street","postalCode","99999","town","Example town")),
                "dog",Map.of("name","Example Dog "+sequence,"sex","FEMALE","breed","Example breed","birthMonth","2024-04","chip","941000001"+String.format("%06d",sequence)),"planId",planId,
                "payment",Map.of("type","MANUAL","firstMonthOption","TODAY"),"consents",Map.of("privacyPolicy",Map.of("accepted",true,"version","v1"),"imageUse",Map.of("granted",false,"version","v1"))));
    }
    ObjectNode request() { return request(plan); }
    String legalVersion() { return mongo.getCollection("clubs").find(new Document("_id",club)).first().get("legal",Document.class).getString("legalTextsVersion"); }
    ObjectNode canicRequest(String planId) {
        var body=request(planId);String version=legalVersion();
        body.set("consents",mapper.valueToTree(Map.of("privacyPolicy",Map.of("accepted",true,"version",version),"imageUse",Map.of("granted",false,"version",version))));return body;
    }
    MockHttpServletRequestBuilder postJson(String path,Object body) throws Exception {return post("/api/v1"+path).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));}
    MockHttpServletRequestBuilder patchJson(String path,Object body) throws Exception {return patch("/api/v1"+path).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));}
    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) { return request.with(jwt().jwt(j->j.subject("gate-admin").claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))); }
    MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request,String id) {
        String account=member(id).getString("accountId");return request.header("Host",host).with(jwt().jwt(j->j.subject(account).claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_MEMBER")));
    }
    JsonNode result(MockHttpServletRequestBuilder request,int status) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();var body=mapper.readTree(response.getContentAsString().isEmpty()?"{}":response.getContentAsString());
        assertThat(response.getStatus()).as("HTTP code=%s details=%s",body.path("code").asText(),body.path("details")).isEqualTo(status);return body;
    }
    /** Each submission comes from its own fictional address: the R-04-20 limits are per club and IP. */
    JsonNode submit(Object body,int status) throws Exception {
        String ip="198.51.100."+(++sequence%250+1);
        return result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()).with(r->{r.setRemoteAddr(ip);return r;}),status);
    }
    JsonNode submit(Object body) throws Exception { return submit(body,201); }
    Document member(String id) {return mongo.getCollection("members").find(new Document("_id",id).append("clubId",club)).first();}
    List<Document> collection(String name) {return mongo.getCollection(name).find(new Document("clubId",club)).into(new ArrayList<>());}
    List<Document> rows(String memberId) {return mongo.getCollection("upfront_payments").find(new Document("clubId",club).append("memberId",memberId)).into(new ArrayList<>());}
    String pendingDog(String id) {return collection("dogs").stream().filter(d->id.equals(d.getString("memberId"))&&"PENDING".equals(d.getString("status"))).findFirst().orElseThrow().getString("_id");}
    JsonNode review(String id) throws Exception { return result(admin(get("/api/v1/members/"+id+"/signup").header("Host",host)),200); }
    JsonNode signupConfig(String language) throws Exception { signupService.invalidateConfiguration(club);return result(get("/api/v1/signup").header("Host",host).header("Accept-Language",language),200); }
    /** Validates everything pending with the D2 proposals, paying the dry-run total. */
    JsonNode validate(String id) throws Exception {
        var review=review(id);var body=new LinkedHashMap<String,Object>();body.put("version",review.path("version").asLong());
        var dogs=new ArrayList<Map<String,Object>>();for(var dog:review.path("dogs")) dogs.add(Map.of("dogId",dog.path("id").asText(),"levelId",level));body.put("dogs",dogs);
        if(!review.at("/proposals/nextInvoiceDate").isMissingNode()&&"PENDING".equals(member(id).getString("status"))) body.put("nextInvoiceDate",review.at("/proposals/nextInvoiceDate").asText());
        var dry=result(admin(postJson("/members/"+id+"/validation",body).param("dryRun","true")),200);
        body.put("upfrontAmountPaid",mapper.convertValue(dry.at("/upfront/totalDue"),Map.class));
        return result(admin(postJson("/members/"+id+"/validation",body)),200);
    }
    String activeMember(String planId) throws Exception { String id=submit(club.startsWith("gt-")?request(planId):canicRequest(planId)).path("memberId").asText();validate(id);return id; }
    void dispatch() { for(int i=0;i<12;i++) dispatcher.dispatch(); }
    private String administrator(String language) {
        String id=UUID.randomUUID().toString();
        mongo.insert(new com.agilityhub.core.identity.persistence.Account(id,id+"@example.test","Example Admin",language,null,Set.of(),com.agilityhub.core.identity.persistence.Account.Status.ACTIVE,null,Map.of(),false,clock.instant()));
        mongo.insert(new com.agilityhub.core.identity.persistence.Membership(id,id,club,null,Set.of(com.agilityhub.core.identity.domain.Role.ADMIN),com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE,com.agilityhub.core.identity.domain.Role.ADMIN));
        return id;
    }
    Map<String,Object> addDog(String chip) { return Map.of("dog",Map.of("name","Added Dog "+chip.substring(chip.length()-3),"sex","MALE","breed","Example breed","birthMonth","2023-02","chip",chip),"documents",List.of()); }

    // ---- Step 2 (M6) and step 12: texts and flags of GET /signup ----
    @Test void T_04_06_signupTextsHaveNoRawPlaceholdersForTheCanicAndTheMinimalClub() throws Exception {
        canic();
        for(String language:List.of("ca","es","en")) {
            var texts=signupConfig(language).path("texts");
            texts.fields().forEachRemaining(field -> assertThat(field.getValue().asText()).as(language+" "+field.getKey()).doesNotContain("{","}"));
            assertThat(texts.path("paymentDay").asText()).contains("25");
            assertThat(texts.path("familyGroupIntro").asText()).contains("90");
        }
        assertThat(signupConfig("ca").at("/texts/familyGroupIntro").asText()).contains("90,00");
        var input=codec.read(Path.of("seeds/club-minim.yaml"));input.remove("accounts");String slug="minim-"+UUID.randomUUID().toString().substring(0,8);
        ((ObjectNode)input.get("club")).put("slug",slug).put("status","ACTIVE");((ObjectNode)input.at("/domains/0")).put("host",slug+".example.test");
        club=definitions.apply(input,false).id();host=slug+".example.test";hosts.invalidate();
        for(String language:List.of("ca","es","en")) {
            var config=signupConfig(language);var texts=config.path("texts");
            texts.fields().forEachRemaining(field -> assertThat(field.getValue().isNull()?"":field.getValue().asText()).as(language+" "+field.getKey()).doesNotContain("{","}"));
            // No family fare (no plan, no BILLING): the intro is null, never a raw placeholder.
            assertThat(texts.has("familyGroupIntro")).isTrue();assertThat(texts.path("familyGroupIntro").isNull()).isTrue();
        }
    }
    @Test void R_04_08_R_04_12_signupFlagsAreExposedAndStillEnforced() throws Exception {
        var config=signupConfig("ca");
        assertThat(config.path("allowFamilyGroupPending").asBoolean()).isTrue();assertThat(config.path("requireDogDocumentAtSignup").asBoolean()).isFalse();
        parameter("signup.requireDogDocumentAtSignup",true);parameter("signup.allowFamilyGroupPending",false);
        config=signupConfig("ca");
        assertThat(config.path("requireDogDocumentAtSignup").asBoolean()).isTrue();assertThat(config.path("allowFamilyGroupPending").asBoolean()).isFalse();
        assertThat(submit(request(),422).path("code").asText()).isEqualTo("DOG_DOCUMENT_REQUIRED");
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("modules",List.of("BILLING"))));configs.invalidate(club);
        config=signupConfig("ca");assertThat(config.has("allowFamilyGroupPending")).isFalse();assertThat(config.has("requireDogDocumentAtSignup")).isTrue();
    }
    void parameter(String key,Object value) {
        mongo.remove(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is(club).and("key").is(key)),Parameter.class);
        mongo.insert(new Parameter(UUID.randomUUID().toString(),club,key,value,"unknown","club",null,List.of(),0L,clock.instant()));configs.invalidate(club);signupService.invalidateConfiguration(club);
    }

    // ---- Steps 3 and 6 (M7, M9): payment instructions and N-01 ----
    @Test void T_04_14_manualInstructionsComeFromTheClubProviderAndN01CarriesTotalAndInstructions() throws Exception {
        var instructions=Map.of("ca","Paga a la recepció del club de proves.","es","Paga en la recepción del club de pruebas.","en","Pay at the test club desk.");
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("paymentProviders.MANUAL",new Document("instructions",new Document("values",instructions)))));configs.invalidate(club);
        var config=signupConfig("ca");var manual=config.path("paymentMethods").findParents("type").stream().filter(m -> "MANUAL".equals(m.path("type").asText())).findFirst().orElseThrow();
        assertThat(manual.path("instructions").asText()).isEqualTo(instructions.get("ca"));
        assertThat(config.at("/texts/cashConditions").asText()).isNotEqualTo(instructions.get("ca"));
        var mailbox=(com.agilityhub.core.clubs.messaging.application.FakeEmailSender)sender;mailbox.clear();String admin=administrator("ca");
        var body=request();String email=body.at("/person/emails/0").asText();String id=submit(body).path("memberId").asText();dispatch();
        var applicant=mailbox.lastTo(email).text();
        assertThat(applicant).contains("Hem rebut la teva sol·licitud","160,00",instructions.get("ca")).doesNotContain("{","}");
        var copy=mailbox.lastTo(admin+"@example.test");
        assertThat(copy.subject()).isEqualTo("Nova sol·licitud d'alta");
        assertThat(copy.text()).contains("Example "+body.at("/person/lastName1").asText(),body.at("/dog/name").asText(),"(Example)").doesNotContain("Hem rebut la teva");
        var appRows=collection("notifications").stream().filter(n -> "N-01".equals(n.getString("code"))&&"APP".equals(n.getString("channel"))).toList();
        assertThat(appRows).hasSize(1).allSatisfy(n -> {assertThat(n.getString("accountId")).isEqualTo(admin);assertThat(n.get("variables",Document.class).getString("action")).isEqualTo("OPEN_SIGNUP");});
        // The add-dog N-01 (§8: the member gets APP+EMAIL, the admins their own copy with OPEN_SIGNUP; the member's never).
        validate(id);dispatch();mailbox.clear();String account=member(id).getString("accountId");
        result(asMember(postJson("/me/dogs/signup",addDog("941000009000001")).header("Idempotency-Key",UUID.randomUUID()),id),201);dispatch();
        assertThat(mailbox.lastTo(email).text()).contains("Hem rebut la teva sol·licitud per a Added Dog 001","130,00",instructions.get("ca"));
        assertThat(mailbox.lastTo(admin+"@example.test").text()).contains("ha enviat una sol·licitud per a Added Dog 001 (Example)");
        var memberApp=collection("notifications").stream().filter(n -> "N-01".equals(n.getString("code"))&&account.equals(n.getString("accountId"))).findFirst().orElseThrow();
        assertThat(memberApp.get("variables",Document.class).containsKey("action")).isFalse();
    }

    // ---- Step 4 (M5): one quote per plan, on the R-04-14/15 examples of the Cànic ----
    JsonNode quote(JsonNode config,String planId) {
        for(var quote:config.at("/upfront/planQuotes")) if(planId.equals(quote.path("planId").asText())) return quote;
        throw new AssertionError("No quote for "+planId);
    }
    @Test void T_04_05_T_04_06_eachPlanGetsItsOwnQuoteLikeTheSubmission() throws Exception {
        canic();clock.setInstant(Instant.parse("2026-08-17T08:00:00Z"));
        var config=signupConfig("ca");var abonat=quote(config,planId("ABONAT"));
        assertThat(abonat.path("lines")).hasSize(1);assertThat(abonat.at("/lines/0/concept").asText()).isEqualTo("ENTRY_FEE");assertThat(abonat.at("/lines/0/amount/amountMinor").asLong()).isEqualTo(10000);
        assertThat(abonat.path("options")).hasSize(2);
        assertThat(abonat.at("/options/0/option").asText()).isEqualTo("TODAY");assertThat(abonat.at("/options/0/portion").asText()).isEqualTo("HALF");
        assertThat(abonat.at("/options/0/startDate").asText()).isEqualTo("2026-08-17");assertThat(abonat.at("/options/0/amountDue/amountMinor").asLong()).isEqualTo(3000);
        assertThat(abonat.at("/options/0/totalDue/amountMinor").asLong()).isEqualTo(13000);
        assertThat(abonat.at("/options/1/option").asText()).isEqualTo("ALTERNATIVE");assertThat(abonat.at("/options/1/portion").asText()).isEqualTo("FULL");
        assertThat(abonat.at("/options/1/startDate").asText()).isEqualTo("2026-09-01");assertThat(abonat.at("/options/1/totalDue/amountMinor").asLong()).isEqualTo(16000);
        var pack=quote(config,planId("PACK6"));
        assertThat(pack.path("options")).isEmpty();assertThat(pack.at("/totalDue/amountMinor").asLong()).isEqualTo(13500);assertThat(pack.at("/lines/0/concept").asText()).isEqualTo("PACK");
        var therapy=quote(config,planId("TERAPIA"));
        assertThat(therapy.path("options")).isEmpty();assertThat(therapy.at("/totalDue/amountMinor").asLong()).isEqualTo(5000);
        // The quote is the submission: the 130 € of TODAY is what POST /signup freezes and charges.
        var submitted=submit(canicRequest(planId("ABONAT")));assertThat(submitted.at("/upfront/totalDue/amountMinor").asLong()).isEqualTo(13000);
        clock.setInstant(Instant.parse("2026-08-05T08:00:00Z"));abonat=quote(signupConfig("ca"),planId("ABONAT"));
        assertThat(abonat.at("/options/0/portion").asText()).isEqualTo("FULL");assertThat(abonat.at("/options/0/totalDue/amountMinor").asLong()).isEqualTo(16000);
        assertThat(abonat.at("/options/1/portion").asText()).isEqualTo("HALF");assertThat(abonat.at("/options/1/startDate").asText()).isEqualTo("2026-08-16");
        assertThat(abonat.at("/options/1/totalDue/amountMinor").asLong()).isEqualTo(13000);
    }
    @Test void T_04_06_addDogQuoteKeepsTodayAfterTheCutoffDay() throws Exception {
        canic();clock.setInstant(Instant.parse("2026-08-10T08:00:00Z"));String id=activeMember(planId("ABONAT"));
        clock.setInstant(Instant.parse("2026-08-26T08:00:00Z"));signupService.invalidateConfiguration(club);
        var config=result(asMember(get("/api/v1/signup"),id),200);
        assertThat(config.at("/upfront/additionalDogOptions")).hasSize(1);assertThat(config.at("/upfront/additionalDogOptions/0/option").asText()).isEqualTo("TODAY");
        var own=quote(config,planId("ABONAT"));
        assertThat(own.path("options")).hasSize(1);assertThat(own.at("/options/0/option").asText()).isEqualTo("TODAY");
        // R-04-14: entry 100 € + the additional fee of the current month (family fare 90 € − 60 €).
        assertThat(own.at("/options/0/amountDue/amountMinor").asLong()).isEqualTo(3000);assertThat(own.at("/options/0/totalDue/amountMinor").asLong()).isEqualTo(13000);
        clock.setInstant(Instant.parse("2026-08-25T08:00:00Z"));signupService.invalidateConfiguration(club);
        assertThat(quote(result(asMember(get("/api/v1/signup"),id),200),planId("ABONAT")).path("options")).hasSize(2);
    }

    // ---- Step 5 (M8): assignable plans ----
    @Test void T_04_16_foundFamilyClaimProposesAndValidatesTheHiddenFamilyFare() throws Exception {
        canic();clock.setInstant(Instant.parse("2026-08-10T08:00:00Z"));String holder=activeMember(planId("ABONAT"));
        var holderRow=member(holder);var holderDog=collection("dogs").stream().filter(d -> holder.equals(d.getString("memberId"))).findFirst().orElseThrow().getString("name");
        var body=canicRequest(planId("ABONAT"));
        body.set("familyGroupClaim",mapper.valueToTree(Map.of("holderName",holderRow.getString("firstName")+" "+holderRow.getString("lastName1"),"dogName",holderDog,"leavePending",false)));
        String id=submit(body).path("memberId").asText();
        var review=review(id);assertThat(review.at("/familyGroupClaim/status").asText()).isEqualTo("FOUND");
        assertThat(review.at("/proposals/planId").asText()).isEqualTo(planId("ABONAT_FAMILIAR"));
        assertThat(review.path("planOptions").findValuesAsText("planId")).contains(planId("ABONAT_FAMILIAR"),planId("ABONAT"),planId("PACK6"),planId("TERAPIA"));
        var family=review.path("planOptions").findParents("planId").stream().filter(o -> planId("ABONAT_FAMILIAR").equals(o.path("planId").asText())).findFirst().orElseThrow();
        assertThat(family.path("name").asText()).isEqualTo("Abonat · 2 gossos (familiar)");assertThat(family.at("/prices/0/amount/amountMinor").asLong()).isEqualTo(9000);
        assertThat(family.at("/prices/0/periodicity").asText()).isEqualTo("MONTHLY");
        validate(id);
        assertThat(member(id).getString("planId")).isEqualTo(planId("ABONAT_FAMILIAR"));assertThat(member(id).getString("familyGroupId")).isNotNull();
        // A holder with two dogs: the group reaches 3 dogs and no plan includes 3, so the proposal is the largest family fare.
        mongo.insert(new Document("_id",UUID.randomUUID().toString()).append("clubId",club).append("memberId",holder).append("name","Second Holder Dog")
                .append("status","ACTIVE").append("sex","MALE").append("breed","Example breed").append("chip","941000007000001").append("version",0L),"dogs");
        var third=canicRequest(planId("ABONAT"));third.set("familyGroupClaim",body.get("familyGroupClaim"));
        assertThat(review(submit(third).path("memberId").asText()).at("/proposals/planId").asText()).isEqualTo(planId("ABONAT_FAMILIAR"));
    }
    @Test void T_04_21_familyFareMemberAddsADogAndReadsTheSignupConfiguration() throws Exception {
        canic();clock.setInstant(Instant.parse("2026-08-10T08:00:00Z"));String id=activeMember(planId("ABONAT"));
        mongo.getCollection("members").updateOne(new Document("_id",id),new Document("$set",new Document("planId",planId("ABONAT_FAMILIAR"))));
        for(String day:List.of("2026-08-01","2026-08-17","2026-08-25")) {
            clock.setInstant(Instant.parse(day+"T08:00:00Z"));signupService.invalidateConfiguration(club);
            var config=result(asMember(get("/api/v1/signup"),id),200);
            assertThat(config.at("/member/planId").asText()).isEqualTo(planId("ABONAT_FAMILIAR"));
            assertThat(quote(config,planId("ABONAT_FAMILIAR")).path("options")).hasSize(2);
        }
        var added=result(asMember(postJson("/me/dogs/signup",addDog("941000009000002")).header("Idempotency-Key",UUID.randomUUID()),id),201);
        assertThat(added.path("dogId").asText()).isNotBlank();
    }
    @Test void T_04_18_D2LoadsAfterTheRequestedPlanIsHidden() throws Exception {
        String id=submit(request()).path("memberId").asText();
        mongo.getCollection("plans").updateOne(new Document("_id",plan),new Document("$set",new Document("showOnSignup",false)));signupService.invalidateConfiguration(club);
        var review=review(id);assertThat(review.at("/proposals/planId").asText()).isEqualTo(plan);
        assertThat(review.path("planOptions").findValuesAsText("planId")).containsExactly(plan);
        validate(id);assertThat(member(id).getString("planId")).isEqualTo(plan);
        // The public offer is unchanged: an applicant cannot request the hidden plan.
        assertThat(submit(request(),422).path("code").asText()).isEqualTo("PLAN_NOT_AVAILABLE");
    }
    @Test void T_04_09_publicSignupWithAHiddenPlanIsNotAvailable() throws Exception {
        canic();clock.setInstant(Instant.parse("2026-08-10T08:00:00Z"));
        assertThat(signupConfig("ca").path("plans").findValuesAsText("id")).doesNotContain(planId("ABONAT_FAMILIAR"));
        assertThat(submit(canicRequest(planId("ABONAT_FAMILIAR")),422).path("code").asText()).isEqualTo("PLAN_NOT_AVAILABLE");
    }

    // ---- Step 7 (M10, E39): upfront rows per submission ----
    @Test void T_04_12_readmissionSeesOnlyTheRowsOfItsOwnSubmission() throws Exception {
        var original=request();String id=submit(original).path("memberId").asText();
        var entry=rows(id).stream().filter(p -> "ENTRY_FEE".equals(p.getString("concept"))).findFirst().orElseThrow();
        mongo.getCollection("upfront_payments").updateOne(new Document("_id",entry.getString("_id")),new Document("$set",new Document("status","PAID").append("amountPaid",entry.get("amountDue"))));
        result(admin(postJson("/members/"+id+"/rejection",Map.of("version",member(id).get("version"),"reason","Fictional first rejection"))),200);
        assertThat(submit(original).path("memberId").asText()).isEqualTo(id);
        String submission=member(id).get("signup",Document.class).getString("submissionId");
        var review=review(id);
        assertThat(review.at("/upfront/lines")).hasSize(2).allSatisfy(line -> assertThat(line.path("status").asText()).isEqualTo("DUE"));
        assertThat(review.at("/upfront/totalPaid/amountMinor").asLong()).isZero();assertThat(review.at("/upfront/totalDue/amountMinor").asLong()).isEqualTo(16000);
        assertThat(review.path("warnings").toString()).contains("UPFRONT_UNPAID","READMISSION");
        validate(id);
        assertThat(rows(id).stream().filter(p -> submission.equals(p.getString("submissionId")))).hasSize(2).allMatch(p -> "PAID".equals(p.getString("status")));
        var old=rows(id).stream().filter(p -> p.getString("_id").equals(entry.getString("_id"))).findFirst().orElseThrow();
        assertThat(old.getString("status")).isEqualTo("PAID");assertThat(old.get("submissionId")).isNotEqualTo(submission);
    }
    @Test void T_04_18_planChangeKeepsAPartialRowIntactAndDeductsIt() throws Exception {
        String id=submit(request()).path("memberId").asText();String dog=pendingDog(id);String pack=pack();
        var entry=rows(id).stream().filter(p -> "ENTRY_FEE".equals(p.getString("concept"))).findFirst().orElseThrow();
        mongo.getCollection("upfront_payments").updateOne(new Document("_id",entry.getString("_id")),new Document("$set",new Document("status","PARTIAL").append("amountPaid",new Document("amountMinor",5000L).append("currency","EUR"))));
        var body=new LinkedHashMap<String,Object>(Map.of("version",member(id).get("version"),"dogs",List.of(Map.of("dogId",dog,"levelId",level)),"planId",pack));
        var dry=result(admin(postJson("/members/"+id+"/validation",body).param("dryRun","true")),200);
        assertThat(dry.at("/upfront/totalDue/amountMinor").asLong()).isEqualTo(8500);assertThat(dry.at("/upfront/totalPaid/amountMinor").asLong()).isEqualTo(5000);
        assertThat(dry.at("/upfront/lines").findValuesAsText("id")).contains(entry.getString("_id"));
        body.put("upfrontAmountPaid",Map.of("amountMinor",8500,"currency","EUR"));result(admin(postJson("/members/"+id+"/validation",body)),200);
        var kept=rows(id).stream().filter(p -> p.getString("_id").equals(entry.getString("_id"))).findFirst().orElseThrow();
        // The row keeps its own amount; only the later payment moved amountPaid.
        assertThat(kept.get("amountDue",Document.class).get("amountMinor",Number.class).longValue()).isEqualTo(10000);
        assertThat(rows(id).stream().filter(p -> "FIRST_MONTH".equals(p.getString("concept"))).map(p -> p.getString("status"))).containsExactly("CANCELLED");
        assertThat(rows(id).stream().filter(p -> "PACK".equals(p.getString("concept"))).map(p -> p.get("amountDue",Document.class).get("amountMinor",Number.class).longValue())).containsExactly(3500L);
        assertThat(rows(id).stream().filter(p -> !"CANCELLED".equals(p.getString("status")))).allMatch(p -> "PAID".equals(p.getString("status")));
    }
    @Test void T_04_22_planChangeWithACheckoutInProgressIsRefused() throws Exception {
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("paymentProviders",new Document("MANUAL",new Document("enabled",true)).append("SEPA_XML",new Document("enabled",true)).append("STRIPE",new Document("enabled",true)))));
        configs.invalidate(club);signupService.invalidateConfiguration(club);
        var body=request();body.set("payment",mapper.valueToTree(Map.of("type","CARD","firstMonthOption","TODAY")));var submitted=submit(body);String id=submitted.path("memberId").asText();
        result(postJson("/checkout-sessions",Map.of("memberId",id,"signupToken",submitted.path("signupToken").asText(),"successUrl","https://"+host+"/ok","cancelUrl","https://"+host+"/cancel")).header("Idempotency-Key",UUID.randomUUID()),201);
        assertThat(rows(id)).allMatch(p -> "CHECKOUT_PENDING".equals(p.getString("status")));
        var request=new LinkedHashMap<String,Object>(Map.of("version",member(id).get("version"),"dogs",List.of(Map.of("dogId",pendingDog(id),"levelId",level)),"planId",pack()));
        var dry=result(admin(postJson("/members/"+id+"/validation",request).param("dryRun","true")),200);
        assertThat(dry.path("warnings").toString()).contains("CHECKOUT_PENDING");
        var refused=result(admin(postJson("/members/"+id+"/validation",request)),409);
        assertThat(refused.path("code").asText()).isEqualTo("INVALID_STATE");assertThat(refused.at("/details/reason").asText()).isEqualTo("CHECKOUT_PENDING");
        assertThat(rows(id)).allMatch(p -> "CHECKOUT_PENDING".equals(p.getString("status")));
    }
    String pack() {
        String id=UUID.randomUUID().toString();var name=new LocalizedText(Map.of("ca","Pack","es","Pack","en","Pack"),"en");
        mongo.insert(new Plan(id,club,"PACK_"+sequence++,name,PlanType.PACK,null,1,new EntryFee(EntryFeeMode.NONE,null,null),new Pack(6,3),null,name,new Texts(name,name,name),true,true,1,true,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Price(UUID.randomUUID().toString(),club,id,PriceConcept.PACK,new Money(13500,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2020,1,1),null,0,clock.instant(),clock.instant(),null,null));
        signupService.invalidateConfiguration(club);return id;
    }

    // ---- Step 8 (M11): warnDays and NOT_PENDING in D2 ----
    @Test void T_04_24_reviewCarriesWarnDaysAndNothingPendingIsNotPending() throws Exception {
        String id=submit(request()).path("memberId").asText();
        assertThat(review(id).path("warnDays").asInt()).isEqualTo(2);
        validate(id);
        var closed=result(admin(get("/api/v1/members/"+id+"/signup").header("Host",host)),409);
        assertThat(closed.path("code").asText()).isEqualTo("INVALID_STATE");assertThat(closed.at("/details/reason").asText()).isEqualTo("NOT_PENDING");
    }

    // ---- Step 9 (M20, E36): pending dogs on screen 13 ----
    @Test void T_04_21_R_04_25_myDogsListsTheOwnPendingDogUntilValidationOrRejection() throws Exception {
        String id=activeMember(plan);
        String dog=result(asMember(postJson("/me/dogs/signup",addDog("941000009000003")).header("Idempotency-Key",UUID.randomUUID()),id),201).path("dogId").asText();
        var pending=myDogs(id).get(dog);
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.fieldNames()).toIterable().containsExactlyInAnyOrder("id","name","breed","sex","ageYears","status");
        assertThat(myDogs(id).values().stream().map(d -> d.path("status").asText())).containsExactlyInAnyOrder("ACTIVE","PENDING");
        validate(id);
        assertThat(myDogs(id).get(dog).path("status").asText()).isEqualTo("ACTIVE");assertThat(myDogs(id).get(dog).has("documents")).isTrue();
        String rejected=result(asMember(postJson("/me/dogs/signup",addDog("941000009000004")).header("Idempotency-Key",UUID.randomUUID()),id),201).path("dogId").asText();
        assertThat(myDogs(id)).containsKey(rejected);
        result(admin(postJson("/members/"+id+"/rejection",Map.of("version",member(id).get("version"),"reason","Fictional add-dog rejection"))),200);
        assertThat(myDogs(id)).doesNotContainKey(rejected).hasSize(2);
    }
    Map<String,JsonNode> myDogs(String id) throws Exception {
        var dogs=new LinkedHashMap<String,JsonNode>();for(var dog:result(asMember(get("/api/v1/me/dogs"),id),200).path("dogs")) dogs.put(dog.path("id").asText(),dog);return dogs;
    }

    // ---- Step 10 (M21): the §3 constraints on the server ----
    @Test void T_04_18_nextInvoiceDateBeforeTheFirstMonthStartIsRejected() throws Exception {
        String id=submit(request()).path("memberId").asText();
        var body=new LinkedHashMap<String,Object>(Map.of("version",member(id).get("version"),"dogs",List.of(Map.of("dogId",pendingDog(id),"levelId",level)),"nextInvoiceDate","2025-12-31",
                "upfrontAmountPaid",Map.of("amountMinor",16000,"currency","EUR")));
        for(boolean dry:List.of(true,false)) {
            var error=result(admin(postJson("/members/"+id+"/validation",body).param("dryRun",String.valueOf(dry))),400);
            assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");assertThat(error.at("/details/fieldErrors/0/field").asText()).isEqualTo("nextInvoiceDate");
        }
        body.put("nextInvoiceDate","2026-01-01");result(admin(postJson("/members/"+id+"/validation",body)),200);
    }
    @Test void T_04_02_birthDateMustBeInThePastAndNotBefore1900() throws Exception {
        for(String date:List.of("1899-12-31","2026-01-01","2026-06-01")) {
            var body=request();((ObjectNode)body.get("person")).put("birthDate",date);
            assertThat(submit(body,400).at("/details/fieldErrors/0/field").asText()).as(date).isEqualTo("birthDate");
        }
        var body=request();((ObjectNode)body.get("person")).put("birthDate","1900-01-01");String id=submit(body).path("memberId").asText();
        result(admin(patchJson("/members/"+id,Map.of("version",member(id).get("version"),"birthDate","1899-12-31"))),400);
    }
    @Test void T_04_07_chipIsNormalisedAndCheckedPerCountryProfile() throws Exception {
        var first=request();((ObjectNode)first.get("dog")).put("chip","941000008000001");String id=submit(first).path("memberId").asText();
        var spaced=request();((ObjectNode)spaced.get("dog")).put("chip","941 000 008-000 001");
        assertThat(submit(spaced,422).path("code").asText()).isEqualTo("DOG_CHIP_ALREADY_REGISTERED");
        for(String chip:List.of("94100000800000","9410000080000011","94100000800000A")) {
            var body=request();((ObjectNode)body.get("dog")).put("chip",chip);
            assertThat(submit(body,400).at("/details/fieldErrors/0/field").asText()).as(chip).isEqualTo("dog.chip");
        }
        var typed=request();((ObjectNode)typed.get("dog")).put("chip","941-000-008-000-002");String other=submit(typed).path("memberId").asText();
        assertThat(collection("dogs").stream().filter(d -> other.equals(d.getString("memberId"))).findFirst().orElseThrow().getString("chip")).isEqualTo("941000008000002");
        // The D2 dog PATCH of a pending dog: normalised and checked too.
        String dog=pendingDog(id);long version=collection("dogs").stream().filter(d -> dog.equals(d.getString("_id"))).findFirst().orElseThrow().get("version",Number.class).longValue();
        result(admin(patchJson("/dogs/"+dog,Map.of("version",version,"chip","ABC"))),400);
        result(admin(patchJson("/dogs/"+dog,Map.of("version",version,"chip","941 000 008 000 009"))),200);
        assertThat(collection("dogs").stream().filter(d -> dog.equals(d.getString("_id"))).findFirst().orElseThrow().getString("chip")).isEqualTo("941000008000009");
        // Add-dog uses the same rule.
        validate(id);
        assertThat(result(asMember(postJson("/me/dogs/signup",addDog("12345")).header("Idempotency-Key",UUID.randomUUID()),id),400).at("/details/fieldErrors/0/field").asText()).isEqualTo("dog.chip");
    }

    // ---- Step 11 (M12): the dog's own version in D2 ----
    @Test void R_04_19_reviewDogCarriesTheVersionThatTheDogPatchCompares() throws Exception {
        String id=activeMember(plan);
        String dog=result(asMember(postJson("/me/dogs/signup",addDog("941000009000005")).header("Idempotency-Key",UUID.randomUUID()),id),201).path("dogId").asText();
        result(admin(patchJson("/members/"+id,Map.of("version",member(id).get("version"),"remarks","Fictional remark"))),200);
        result(admin(patchJson("/dogs/"+dog,Map.of("version",review(id).at("/dogs/0/version").asLong(),"name","Renamed Dog"))),200);
        var review=review(id);assertThat(review.at("/dogs/0").has("version")).isTrue();
        assertThat(review.at("/dogs/0/version").asLong()).isNotEqualTo(review.path("version").asLong());
        result(admin(patchJson("/dogs/"+dog,Map.of("version",review.at("/dogs/0/version").asLong(),"breed","Updated breed"))),200);
    }
}

package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * E3-T14: the payment methods of a signup (R-04-10). Only the methods of the club's **enabled** providers are offered by
 * `GET /signup` and accepted by `POST /signup` and the D2 `PATCH /members/{id}`; `configured` never gates them. The D2 view
 * publishes the methods D2 may assign, plus the applicant's current one. Same Spring context as {@link SignupIT}.
 */
@org.springframework.boot.test.context.SpringBootTest(properties={"shared.scheduling.enabled=false","core.security.rate-limits.enabled=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class SignupPaymentMethodsIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired MongoTemplate mongo;@Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;@Autowired HostTenantResolver hosts;
    @Autowired com.agilityhub.core.clubs.census.application.SignupService signupService;
    String club,host,plan,level;
    int sequence;
    @BeforeEach void fixtureClub() {
        club="pm-"+UUID.randomUUID();host=club+".example.test";plan=UUID.randomUUID().toString();level=UUID.randomUUID().toString();
        ObjectNode tree=mapper.valueToTree(PlatformFixtures.club(club,host));tree.set("modules",mapper.valueToTree(Module.values()));
        // The offer follows the configured order (R-04-10), so the fixture keeps one: direct debit, then cash.
        var providers=tree.putObject("paymentProviders");providers.putObject("SEPA_XML").put("enabled",true);providers.putObject("MANUAL").put("enabled",true);
        clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(club);hosts.invalidate();
        var name=new LocalizedText(Map.of("ca","Example","es","Example","en","Example"),"en");
        mongo.insert(new Plan(plan,club,"MONTHLY",name,PlanType.MONTHLY,BillingMode.MONTHLY_FEE,1,new EntryFee(EntryFeeMode.STANDARD,null,null),null,null,name,null,true,true,0,true,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Price(UUID.randomUUID().toString(),club,plan,PriceConcept.MONTHLY_FEE,new Money(6000,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2020,1,1),null,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Level(level,club,"A",new LocalizedText(Map.of("ca","Iniciació","es","Iniciación","en","Beginner"),"en"),0,"#000000",8,false,true,true,0,clock.instant(),clock.instant(),null,null));
        signupService.invalidateConfiguration(club);
    }
    /** Replaces the club's `paymentProviders` as stored, in this order. */
    void providers(Document providers) {
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("paymentProviders",providers)));
        configs.invalidate(club);signupService.invalidateConfiguration(club);
    }
    static Document enabled(boolean value) { return new Document("enabled",value); }
    ObjectNode request(String paymentType) {
        int value=15000000+(++sequence);String national=String.format("%08d",value)+"TRWAGMYFPDXBNJZSQVHLCKE".charAt(value%23);
        return mapper.valueToTree(Map.of("locale","ca","website","","person",Map.of("idDocument",Map.of("type","DNI","value",national),"firstName","Example","lastName1","Payer"+sequence,"birthDate","2000-01-01","gender","FEMALE","emails",List.of("payer"+sequence+"."+club.substring(3,11)+"@example.test"),"phones",List.of(Map.of("prefix","+34","number","600000001")),"address",Map.of("street","Example street","postalCode","99999","town","Example town")),
                "dog",Map.of("name","Example Dog "+sequence,"sex","FEMALE","breed","Example breed","birthMonth","2024-04","chip","941000003"+String.format("%06d",sequence)),"planId",plan,
                "payment",Map.of("type",paymentType,"firstMonthOption","TODAY"),"consents",Map.of("privacyPolicy",Map.of("accepted",true,"version","v1"),"imageUse",Map.of("granted",false,"version","v1"))));
    }
    MockHttpServletRequestBuilder patchJson(String path,Object body) throws Exception {return patch("/api/v1"+path).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));}
    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) { return request.header("Host",host).with(jwt().jwt(j->j.subject("payment-admin").claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))); }
    JsonNode result(MockHttpServletRequestBuilder request,int status) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();var body=mapper.readTree(response.getContentAsString().isEmpty()?"{}":response.getContentAsString());
        assertThat(response.getStatus()).as("HTTP code=%s details=%s",body.path("code").asText(),body.path("details")).isEqualTo(status);return body;
    }
    /** Each submission comes from its own fictional address: the R-04-20 limits are per club and IP. */
    JsonNode submit(Object body,int status) throws Exception {
        String ip="198.51.100."+(++sequence%250+1);
        return result(post("/api/v1/signup").header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body))
                .header("Idempotency-Key",UUID.randomUUID()).header("User-Agent","Fictional agent/1.0").with(r->{r.setRemoteAddr(ip);return r;}),status);
    }
    Document member(String id) {return mongo.getCollection("members").find(new Document("_id",id).append("clubId",club)).first();}
    long members() { return mongo.getCollection("members").countDocuments(new Document("clubId",club)); }
    List<String> offered(String language) throws Exception {
        signupService.invalidateConfiguration(club);
        return result(get("/api/v1/signup").header("Host",host).header("Accept-Language",language),200).path("paymentMethods").findValuesAsText("type");
    }
    JsonNode review(String id,String language) throws Exception { return result(admin(get("/api/v1/members/"+id+"/signup")).header("Accept-Language",language),200); }
    JsonNode option(String type,String label,boolean current,boolean assignable) {
        return mapper.valueToTree(Map.of("type",type,"label",label,"current",current,"assignable",assignable));
    }
    int patchPayment(String id,Map<String,Object> payment) throws Exception {
        return mvc.perform(admin(patchJson("/members/"+id,Map.of("version",member(id).get("version"),"paymentMethod",payment)))).andReturn().getResponse().getStatus();
    }
    String code(MockHttpServletRequestBuilder request) throws Exception { return mapper.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString()).path("code").asText(); }
    MockHttpServletRequestBuilder postJson(String path,Object body) throws Exception {return post("/api/v1"+path).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));}
    MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request,String id) {
        String account=member(id).getString("accountId");return request.header("Host",host).with(jwt().jwt(j->j.subject(account).claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_MEMBER")));
    }
    String pendingDog(String id) {
        return mongo.getCollection("dogs").find(new Document("clubId",club).append("memberId",id).append("status","PENDING")).first().getString("_id");
    }
    /** D2 validation of the pending signup, with the dry run's next invoice date and upfront total (as in {@code SignupFollowUpFixesIT}). */
    void validate(String id) throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("version",review(id,"ca").path("version").asLong());
        body.put("dogs",List.of(Map.of("dogId",pendingDog(id),"levelId",level)));
        var dry=result(admin(postJson("/members/"+id+"/validation",body).param("dryRun","true")),200);
        if("PENDING".equals(member(id).getString("status"))) body.put("nextInvoiceDate",dry.path("nextInvoiceDate").asText());
        body.put("upfrontAmountPaid",mapper.convertValue(dry.at("/upfront/totalDue"),Map.class));
        result(admin(postJson("/members/"+id+"/validation",body)),200);
    }

    // ---- Step 1 (R-04-10): one rule for the offer — enabled providers only ----
    @Test void R_04_10_T_04_14_onlyEnabledProvidersAreOfferedAndGetClubShowsTheSameFlags() throws Exception {
        // The seed's shape before E3-T14 (a listed provider without `enabled`) and a configured provider that is not enabled.
        providers(new Document("SEPA_XML",enabled(true)).append("STRIPE",new Document("secretKeyEnc","fictional-encrypted")).append("MANUAL",new Document()));
        assertThat(offered("ca")).containsExactly("SEPA_DD");
        var flags=result(admin(get("/api/v1/club")),200).path("paymentProviders");
        // `configured` does not gate the offer: SEPA without a creditor is offered; STRIPE with credentials is not.
        assertThat(flags.path("SEPA_XML")).isEqualTo(mapper.valueToTree(Map.of("configured",false,"enabled",true)));
        assertThat(flags.path("STRIPE")).isEqualTo(mapper.valueToTree(Map.of("configured",true,"enabled",false)));
        assertThat(flags.path("MANUAL")).isEqualTo(mapper.valueToTree(Map.of("configured",false,"enabled",false)));
        // A provider that is disabled later disappears; the offer follows the configured order.
        providers(new Document("MANUAL",enabled(true)).append("STRIPE",enabled(true)).append("SEPA_XML",enabled(true)));
        assertThat(offered("ca")).containsExactly("MANUAL","CARD","SEPA_DD");
        providers(new Document("MANUAL",enabled(true)).append("STRIPE",enabled(false).append("publishableKey","pk_test_fictional")).append("SEPA_XML",enabled(true)));
        assertThat(offered("ca")).containsExactly("MANUAL","SEPA_DD");
    }
    @Test void R_04_10_T_04_14_aMethodWhoseProviderIsNotEnabledIsRefusedAtSubmission() throws Exception {
        providers(new Document("SEPA_XML",enabled(true)).append("STRIPE",new Document("secretKeyEnc","fictional-encrypted")).append("MANUAL",new Document()));
        for(String type:List.of("MANUAL","CARD")) {
            assertThat(submit(request(type),422).path("code").asText()).as(type).isEqualTo("PAYMENT_METHOD_NOT_AVAILABLE");
        }
        providers(new Document("SEPA_XML",enabled(true)).append("MANUAL",enabled(false)));
        assertThat(submit(request("MANUAL"),422).path("code").asText()).isEqualTo("PAYMENT_METHOD_NOT_AVAILABLE");
        assertThat(members()).isZero();
        String id=submit(request("SEPA_DD"),201).path("memberId").asText();
        assertThat(((Document)member(id).get("paymentMethod")).getString("type")).isEqualTo("SEPA_DD");
    }
    @Test void R_04_19_T_04_20_theD2EditRefusesAMethodWhoseProviderIsNotEnabled() throws Exception {
        String id=submit(request("SEPA_DD"),201).path("memberId").asText();
        providers(new Document("SEPA_XML",enabled(true)).append("STRIPE",new Document("secretKeyEnc","fictional-encrypted")).append("MANUAL",new Document()));
        for(String type:List.of("MANUAL","CARD")) {
            assertThat(code(admin(patchJson("/members/"+id,Map.of("version",member(id).get("version"),"paymentMethod",Map.of("type",type)))))).as(type).isEqualTo("PAYMENT_METHOD_NOT_AVAILABLE");
        }
        assertThat(((Document)member(id).get("paymentMethod")).getString("type")).isEqualTo("SEPA_DD");
        assertThat(patchPayment(id,Map.of("type","SEPA_DD","sepa",Map.of("iban","ES5500000000000000000001")))).isEqualTo(200);
        // A provider disabled after the submission: its method cannot be assigned again, even to the applicant who has it.
        providers(new Document("SEPA_XML",enabled(false)).append("MANUAL",enabled(true)));
        assertThat(code(admin(patchJson("/members/"+id,Map.of("version",member(id).get("version"),"paymentMethod",Map.of("type","SEPA_DD")))))).isEqualTo("PAYMENT_METHOD_NOT_AVAILABLE");
        assertThat(patchPayment(id,Map.of("type","MANUAL"))).isEqualTo(200);
        assertThat(((Document)member(id).get("paymentMethod")).getString("type")).isEqualTo("MANUAL");
    }

    // ---- Step 3 (web E3-W07 round 2): the D2 view publishes the assignable methods ----
    @Test void R_04_19_T_04_20_theD2ViewPublishesTheAssignableMethodsAndAlwaysTheApplicantsCurrentOne() throws Exception {
        String id=submit(request("MANUAL"),201).path("memberId").asText();
        assertThat(review(id,"ca").path("paymentMethods")).containsExactly(option("SEPA_DD","Domiciliació",false,true),option("MANUAL","Efectiu",true,true));
        assertThat(review(id,"es").path("paymentMethods")).containsExactly(option("SEPA_DD","Domiciliación",false,true),option("MANUAL","Efectivo",true,true));
        // MANUAL disabled after the submission: D2 still shows it as the current method, marked as not assignable.
        providers(new Document("SEPA_XML",enabled(true)).append("STRIPE",new Document("secretKeyEnc","fictional-encrypted")).append("MANUAL",enabled(false)));
        assertThat(review(id,"ca").path("paymentMethods")).containsExactly(option("SEPA_DD","Domiciliació",false,true),option("MANUAL","Efectiu",true,false));
        assertThat(patchPayment(id,Map.of("type","MANUAL"))).isEqualTo(422);
        assertThat(patchPayment(id,Map.of("type","SEPA_DD"))).isEqualTo(200);
        // Once the applicant has another method, the disabled one is gone.
        assertThat(review(id,"ca").path("paymentMethods")).containsExactly(option("SEPA_DD","Domiciliació",true,true));
        // The same rule as GET /signup: what D2 may assign is what the public form offers.
        assertThat(review(id,"ca").path("paymentMethods").findValuesAsText("type")).isEqualTo(offered("ca"));
    }
    @Test void R_04_10_T_04_26_withoutBillingTheD2ViewHasNoPaymentMethods() throws Exception {
        String id=submit(request("MANUAL"),201).path("memberId").asText();
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("modules",List.of())));
        configs.invalidate(club);
        var view=review(id,"ca");
        assertThat(view.path("paymentMethods").isArray()).isTrue();assertThat(view.path("paymentMethods")).isEmpty();
    }
    /**
     * Round 2 (review #1, R-04-19): an add-dog leaves the member ACTIVE, and D2 then edits only the pending dogs; the method
     * changes through D10. The view lists only the member's current method, not assignable, and the PATCH refuses it.
     */
    @Test void R_04_19_T_04_20_anAddDogViewListsOnlyTheCurrentMethodAndItIsNotAssignable() throws Exception {
        String id=submit(request("MANUAL"),201).path("memberId").asText();validate(id);
        assertThat(member(id).getString("status")).isEqualTo("ACTIVE");
        result(asMember(postJson("/me/dogs/signup",Map.of("dog",Map.of("name","Added Dog","sex","MALE","breed","Example breed","birthMonth","2023-02","chip","941000008"+String.format("%06d",++sequence)),"documents",List.of()))
                .header("Idempotency-Key",UUID.randomUUID()),id),201);
        // Both providers stay enabled: the view still offers nothing D2 could assign.
        assertThat(offered("ca")).containsExactly("SEPA_DD","MANUAL");
        assertThat(review(id,"ca").path("paymentMethods")).containsExactly(option("MANUAL","Efectiu",true,false));
        var refused=result(admin(patchJson("/members/"+id,Map.of("version",member(id).get("version"),"paymentMethod",Map.of("type","SEPA_DD")))),400);
        assertThat(refused.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(refused.at("/details/fieldErrors/0")).isEqualTo(mapper.valueToTree(Map.of("field","paymentMethod","code","READ_ONLY")));
        assertThat(((Document)member(id).get("paymentMethod")).getString("type")).isEqualTo("MANUAL");
    }
    /**
     * Round 2 (review #2, R-04-06): during a pending readmission the D2 PATCH edits the submitted method, so `current` marks
     * the submitted one, never the method the LEFT record keeps until validation.
     */
    @Test void R_04_06_T_04_20_aPendingReadmissionMarksTheSubmittedMethodAsCurrent() throws Exception {
        var original=request("SEPA_DD");String id=submit(original,201).path("memberId").asText();validate(id);
        mongo.getCollection("members").updateOne(new Document("_id",id),new Document("$set",new Document("status","LEFT").append("leftAt",Date.from(Instant.parse("2025-06-30T10:00:00Z")))
                .append("leftReason","LEAVE_REQUEST").append("leaveDate","2025-06-30")));
        mongo.getCollection("dogs").updateMany(new Document("memberId",id),new Document("$set",new Document("status","INACTIVE").append("deactivationReason","MEMBER_LEFT")));
        var readmission=original.deepCopy();readmission.set("payment",mapper.valueToTree(Map.of("type","MANUAL","firstMonthOption","TODAY")));
        assertThat(submit(readmission,201).path("memberId").asText()).isEqualTo(id);
        assertThat(((Document)member(id).get("paymentMethod")).getString("type")).as("the LEFT record keeps its method").isEqualTo("SEPA_DD");
        var view=review(id,"ca");
        assertThat(view.at("/signup/readmission").asBoolean()).isTrue();
        assertThat(view.path("paymentMethods")).containsExactly(option("SEPA_DD","Domiciliació",false,true),option("MANUAL","Efectiu",true,true));
        // The D2 PATCH edits the submitted method, and `current` follows it; the record is still untouched.
        assertThat(patchPayment(id,Map.of("type","SEPA_DD"))).isEqualTo(200);
        assertThat(review(id,"ca").path("paymentMethods")).containsExactly(option("SEPA_DD","Domiciliació",true,true),option("MANUAL","Efectiu",false,true));
        assertThat(patchPayment(id,Map.of("type","MANUAL"))).isEqualTo(200);
        assertThat(review(id,"ca").path("paymentMethods")).containsExactly(option("SEPA_DD","Domiciliació",false,true),option("MANUAL","Efectiu",true,true));
        assertThat(((Document)member(id).get("paymentMethod")).getString("type")).isEqualTo("SEPA_DD");
    }

    // ---- E3-T16 round 2, point 5 (web E3-W08 round 2): the snapshot declares every null these responses send ----
    /**
     * The core sends an absent optional field as `null` wherever the schema serializes every property (`Member`,
     * `PaymentMethodView`, `Address`…). Real responses are validated against the committed snapshot with a JSON Schema
     * 2020-12 validator, so a `null` the snapshot does not declare fails (and so does any other shape it does not publish):
     * public submissions by cash and by direct debit without an IBAN and their D2 views, an add-dog and its view, a pending
     * readmission and its view, and a view without BILLING. E5-T16 step 3: named by the rules of the D2 view (R-04-19) and of
     * the two submission results (R-04-25, R-04-26), as T-04-29 and T-04-33 are web UI tests.
     */
    @Test void R_04_19_R_04_25_R_04_26_theD2ViewAndTheSubmissionResultsDeclareEveryNullTheySend() throws Exception {
        var problems=new ArrayList<String>();
        java.util.function.BiConsumer<JsonNode,String> conforms=(value,schema) -> {
            assertThat(value.isObject()).as(schema).isTrue();
            com.agilityhub.core.support.SnapshotSchemas.violations(value,schema).forEach(violation -> problems.add(schema+" "+violation));
        };
        var cashRequest=request("MANUAL");var cash=submit(cashRequest,201);conforms.accept(cash,"SignupResult");
        String cashId=cash.path("memberId").asText();
        var applicant=review(cashId,"ca");conforms.accept(applicant,"MemberSignupView");
        // A found family-group claim: D2 names the holder, a pending applicant with no number and no active dog yet.
        var claimed=request("MANUAL");claimed.set("familyGroupClaim",mapper.valueToTree(Map.of("holderName","Example "+cashRequest.at("/person/lastName1").asText(),
                "dogName",cashRequest.at("/dog/name").asText(),"leavePending",false)));
        var family=review(submit(claimed,201).path("memberId").asText(),"ca");conforms.accept(family,"MemberSignupView");
        assertThat(family.at("/familyGroupClaim/status").asText()).isEqualTo("FOUND");
        assertThat(family.at("/familyGroupClaim/holder/id").asText()).isEqualTo(cashId);
        assertThat(family.at("/familyGroupClaim/holder/memberNumber").isNull()).isTrue();
        assertThat(family.at("/familyGroupClaim/holder/dogs").isArray()).as("a required array, never null").isTrue();
        assertThat(family.at("/familyGroupClaim/holder/dogs")).isEmpty();
        var debit=submit(request("SEPA_DD"),201);conforms.accept(debit,"SignupResult");
        String debitId=debit.path("memberId").asText();
        var noAccount=review(debitId,"es");conforms.accept(noAccount,"MemberSignupView");
        // The web's examples: an applicant has no plan or account yet, and these are sent as null.
        for(var view:List.of(applicant,noAccount)) for(String pointer:List.of("/member/plan","/member/planId","/member/maskedAccount")) {
            assertThat(view.at(pointer).isNull()).as(pointer).isTrue();
        }
        // An add-dog: the member is ACTIVE, with a number and a plan.
        validate(cashId);
        var added=result(asMember(postJson("/me/dogs/signup",Map.of("dog",Map.of("name","Added Dog","sex","MALE","breed","Example breed","birthMonth","2023-02","chip","941000008"+String.format("%06d",++sequence)),"documents",List.of()))
                .header("Idempotency-Key",UUID.randomUUID()),cashId),201);
        conforms.accept(added,"AddDogSignupResult");
        var addDog=review(cashId,"en");conforms.accept(addDog,"MemberSignupView");
        assertThat(addDog.at("/member/status").asText()).isEqualTo("ACTIVE");
        assertThat(addDog.at("/member/memberNumber").isInt()).isTrue();
        assertThat(addDog.at("/member/planId").asText()).isEqualTo(plan);assertThat(addDog.at("/member/plan/id").asText()).isEqualTo(plan);
        // A pending readmission by cash: the submitted method has no account and no channel.
        var original=request("SEPA_DD");String leftId=submit(original,201).path("memberId").asText();validate(leftId);
        mongo.getCollection("members").updateOne(new Document("_id",leftId),new Document("$set",new Document("status","LEFT").append("leftAt",Date.from(Instant.parse("2025-06-30T10:00:00Z")))
                .append("leftReason","LEAVE_REQUEST").append("leaveDate","2025-06-30")));
        mongo.getCollection("dogs").updateMany(new Document("memberId",leftId),new Document("$set",new Document("status","INACTIVE").append("deactivationReason","MEMBER_LEFT")));
        var readmission=original.deepCopy();readmission.set("payment",mapper.valueToTree(Map.of("type","MANUAL","firstMonthOption","TODAY")));
        var readmitted=submit(readmission,201);conforms.accept(readmitted,"SignupResult");
        var returning=review(leftId,"ca");conforms.accept(returning,"MemberSignupView");
        for(String pointer:List.of("/readmission/submitted/paymentMethod/maskedAccount","/readmission/submitted/paymentMethod/channel")) {
            assertThat(returning.at(pointer).isNull()).as(pointer).isTrue();
        }
        // Without BILLING: no upfront block and no methods.
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("modules",List.of())));
        configs.invalidate(club);
        var withoutBilling=review(debitId,"ca");conforms.accept(withoutBilling,"MemberSignupView");
        assertThat(withoutBilling.has("upfront")).isFalse();assertThat(withoutBilling.path("paymentMethods")).isEmpty();
        assertThat(problems).as(String.join("\n",problems)).isEmpty();
    }
}

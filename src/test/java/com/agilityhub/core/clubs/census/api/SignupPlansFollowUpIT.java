package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.definition.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.agilityhub.core.support.SnapshotSchemas;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * E5-T22 steps 1 and 2: what screen 17 needs from `GET /signup` on the real core (web E4-W12 questions 1 and 2), on a copy of
 * the Cànic seed (`seeds/club-canic.yaml`): the Teràpia price text (S05 R-05-19) and, in add-dog mode, the member's own plan
 * even when the public offer hides it (the family fare `ABONAT_FAMILIAR`, S04 R-04-09 and M8, amended 26-09). Same Spring
 * context as {@link SignupIT}.
 */
@org.springframework.boot.test.context.SpringBootTest(properties={"shared.scheduling.enabled=false","core.security.rate-limits.enabled=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class SignupPlansFollowUpIT extends AbstractIntegrationTest {
    /** The Cànic's public offer, in catalog order; `ABONAT_FAMILIAR` (order 10) and `COMPETICIO_1` are hidden. */
    static final List<String> OFFER=List.of("ABONAT","PACK6","PACK10","TERAPIA");
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired MongoTemplate mongo;
    @Autowired HostTenantResolver hosts;@Autowired ClubDefinitions definitions;@Autowired ClubDefinitionCodec codec;
    @Autowired com.agilityhub.core.clubs.census.application.SignupService signupService;
    String club,host,level;
    int sequence;

    @BeforeEach void canic() {
        var input=codec.read(Path.of("seeds/club-canic.yaml"));input.remove("accounts");
        String slug="canic-"+UUID.randomUUID().toString().substring(0,8);
        ((ObjectNode)input.get("club")).put("slug",slug).put("status","ACTIVE");
        ((ObjectNode)input.at("/domains/0")).put("host",slug+".example.test");
        ((ObjectNode)input.at("/domains/1")).put("host","admin."+slug+".example.test");
        club=definitions.apply(input,false).id();host=slug+".example.test";hosts.invalidate();
        level=mongo.getCollection("levels").find(new Document("clubId",club).append("code","A")).first().getString("_id");
        clock.setInstant(Instant.parse("2026-08-10T08:00:00Z"));
    }
    String planId(String code) { return mongo.getCollection("plans").find(new Document("clubId",club).append("code",code)).first().getString("_id"); }
    List<String> planIds(List<String> codes) { return codes.stream().map(this::planId).toList(); }
    Document member(String id) { return mongo.getCollection("members").find(new Document("_id",id).append("clubId",club)).first(); }
    String legalVersion() { return mongo.getCollection("clubs").find(new Document("_id",club)).first().get("legal",Document.class).getString("legalTextsVersion"); }
    ObjectNode request(String planId) {
        int value=16000000+(++sequence);String national=String.format("%08d",value)+"TRWAGMYFPDXBNJZSQVHLCKE".charAt(value%23);String version=legalVersion();
        return mapper.valueToTree(Map.of("locale","ca","website","","person",Map.of("idDocument",Map.of("type","DNI","value",national),"firstName","Example","lastName1","Plans"+sequence,"birthDate","2000-01-01","gender","FEMALE","emails",List.of("plans"+sequence+"."+club.substring(0,8)+"@example.test"),"phones",List.of(Map.of("prefix","+34","number","600000001")),"address",Map.of("street","Example street","postalCode","99999","town","Example town")),
                "dog",Map.of("name","Example Dog "+sequence,"sex","FEMALE","breed","Example breed","birthMonth","2024-04","chip","941000005"+String.format("%06d",sequence)),"planId",planId,
                "payment",Map.of("type","MANUAL","firstMonthOption","TODAY"),"consents",Map.of("privacyPolicy",Map.of("accepted",true,"version",version),"imageUse",Map.of("granted",false,"version",version))));
    }
    MockHttpServletRequestBuilder postJson(String path,Object body) throws Exception { return post("/api/v1"+path).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body)); }
    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) { return request.header("Host",host).with(jwt().jwt(j->j.subject("plans-admin").claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))); }
    MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request,String id) {
        String account=member(id).getString("accountId");return request.header("Host",host).with(jwt().jwt(j->j.subject(account).claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_MEMBER")));
    }
    JsonNode result(MockHttpServletRequestBuilder request,int status) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();var body=mapper.readTree(response.getContentAsString().isEmpty()?"{}":response.getContentAsString());
        assertThat(response.getStatus()).as("HTTP code=%s details=%s",body.path("code").asText(),body.path("details")).isEqualTo(status);return body;
    }
    /** A validated (ACTIVE) member of the copy, through the public signup and D2 with its dry-run total, as in {@code SignupGateFixesIT}. */
    String activeMember(String planId) throws Exception { return activeMember(request(planId)); }
    /** The same with the given signup body; the validation takes D2's proposals (no explicit plan). */
    String activeMember(ObjectNode signup) throws Exception {
        String ip="198.51.100."+(++sequence%250+1);
        String id=result(postJson("/signup",signup).header("Idempotency-Key",UUID.randomUUID()).with(r->{r.setRemoteAddr(ip);return r;}),201).path("memberId").asText();
        var review=result(admin(get("/api/v1/members/"+id+"/signup")),200);var body=new LinkedHashMap<String,Object>();body.put("version",review.path("version").asLong());
        var dogs=new ArrayList<Map<String,Object>>();for(var dog:review.path("dogs")) dogs.add(Map.of("dogId",dog.path("id").asText(),"levelId",level));body.put("dogs",dogs);
        body.put("nextInvoiceDate",review.at("/proposals/nextInvoiceDate").asText());
        var dry=result(admin(postJson("/members/"+id+"/validation",body).param("dryRun","true")),200);
        body.put("upfrontAmountPaid",mapper.convertValue(dry.at("/upfront/totalDue"),Map.class));
        result(admin(postJson("/members/"+id+"/validation",body)),200);
        assertThat(member(id).getString("status")).isEqualTo("ACTIVE");return id;
    }
    JsonNode anonymous(String language) throws Exception { signupService.invalidateConfiguration(club);return result(get("/api/v1/signup").header("Host",host).header("Accept-Language",language),200); }
    JsonNode asMember(String id) throws Exception { return result(asMember(get("/api/v1/signup"),id).header("Accept-Language","ca"),200); }
    static List<String> ids(JsonNode plans) { var ids=new ArrayList<String>();plans.forEach(plan -> ids.add(plan.path("id").asText()));return ids; }
    static JsonNode plan(JsonNode config,String id) {
        for(var plan:config.path("plans")) if(id.equals(plan.path("id").asText())) return plan;
        throw new AssertionError("No plan "+id+" in "+config.path("plans"));
    }
    Map<String,Object> addDog(String chip) { return Map.of("dog",Map.of("name","Added Dog "+chip.substring(chip.length()-3),"sex","MALE","breed","Example breed","birthMonth","2023-02","chip",chip),"documents",List.of()); }
    /**
     * E5-T24 step 7 (review E5-T22 #4): a member on the family fare the real way (R-04-13). An ACTIVE holder on ABONAT with one
     * dog; a public signup on ABONAT that claims the holder's family group; its D2 validation, with D2's proposal, takes the
     * hidden family fare `ABONAT_FAMILIAR` and its price, and joins the holder's group.
     */
    String familyMember() throws Exception {
        String holder=activeMember(planId("ABONAT"));var holderRow=member(holder);
        String holderDog=mongo.getCollection("dogs").find(new Document("memberId",holder).append("clubId",club)).first().getString("name");
        var body=request(planId("ABONAT"));
        body.set("familyGroupClaim",mapper.valueToTree(Map.of("holderName",holderRow.getString("firstName")+" "+holderRow.getString("lastName1"),"dogName",holderDog,"leavePending",false)));
        String id=activeMember(body);
        assertThat(member(id).getString("planId")).isEqualTo(planId("ABONAT_FAMILIAR"));
        assertThat(member(id).getString("priceId")).isEqualTo(mongo.getCollection("prices").find(new Document("clubId",club).append("planId",planId("ABONAT_FAMILIAR"))).first().getString("_id"));
        assertThat(member(id).getString("familyGroupId")).isNotNull().isEqualTo(member(holder).getString("familyGroupId"));
        return id;
    }
    static JsonNode quote(JsonNode config,String planId) {
        for(var quote:config.at("/upfront/planQuotes")) if(planId.equals(quote.path("planId").asText())) return quote;
        throw new AssertionError("No quote for "+planId);
    }
    static long today(JsonNode quote) {
        for(var option:quote.path("options")) if("TODAY".equals(option.path("option").asText())) return option.at("/amountDue/amountMinor").asLong();
        throw new AssertionError("No TODAY option in "+quote);
    }

    /**
     * Step 1 (E4-W12 question 1): every plan with `texts.priceLabel` carries it in the reader's locale; the others have none.
     * This is R-05-19's «sense preu» line on screen 17 for the Cànic (ca/es). T-05-07 as a whole — the lines of the three plan
     * types and «sense preu» in ca, es and en — is {@code OfferCalculatorsTest.T_05_07_localizedPriceLinesForEveryTypeAndNoCurrentPrice}
     * (INC-14, E5-T24's review detail #6).
     */
    @Test void R_05_19_eachPlanCarriesItsPriceLabelInTheReadersLocale() throws Exception {
        var catalan=anonymous("ca");
        assertThat(plan(catalan,planId("TERAPIA")).path("priceLabel").asText()).isEqualTo("condicions i cost segons cada cas");
        assertThat(plan(anonymous("es"),planId("TERAPIA")).path("priceLabel").asText()).isEqualTo("condiciones y coste según cada caso");
        for(String code:List.of("ABONAT","PACK6","PACK10")) assertThat(plan(catalan,planId(code)).has("priceLabel")).as(code).isFalse();
        // The add-dog mode reads the same text.
        assertThat(plan(asMember(activeMember(planId("ABONAT"))),planId("TERAPIA")).path("priceLabel").asText()).isEqualTo("condicions i cost segons cada cas");
        assertThat(SnapshotSchemas.violations(catalan,"SignupConfig")).isEmpty();
        assertThat(SnapshotSchemas.schema("SignupPlan").at("/properties/priceLabel/type").asText()).isEqualTo("string");
        assertThat(SnapshotSchemas.required("SignupPlan")).doesNotContain("priceLabel","current");
    }

    /**
     * Step 2 (E4-W12 question 2, R-04-09, M8): a family member on the hidden `ABONAT_FAMILIAR` finds it in `plans`, in catalog
     * order and marked `current`; the other plans read `current: false`. The public offer still hides it and has no `current`.
     * E5-T24 step 7: the member comes through the family-group signup and its validation (R-04-13), and the family fare's quote
     * is priced on the family price: 50 % of 90 € for a third dog (`billing.familyDiscountPercentFromSecondDog`).
     */
    @Test void R_04_09_theFamilyMembersOwnHiddenPlanIsListedAsCurrentInAddDogMode() throws Exception {
        String id=familyMember();
        var config=asMember(id);
        assertThat(ids(config.path("plans"))).containsExactlyElementsOf(planIds(List.of("ABONAT","ABONAT_FAMILIAR","PACK6","PACK10","TERAPIA")));
        var family=plan(config,planId("ABONAT_FAMILIAR"));
        assertThat(family.path("current").asBoolean()).isTrue();
        assertThat(family.path("name").asText()).isEqualTo("Abonat · 2 gossos (familiar)");
        assertThat(family.at("/price/amount/amountMinor").asLong()).isEqualTo(9000);
        for(var plan:config.path("plans")) if(plan!=family) assertThat(plan.path("current").isBoolean()&&!plan.path("current").asBoolean()).as(plan.path("id").asText()).isTrue();
        // One quote per listed plan, the family fare included (M5), priced on the family price (R-04-14).
        assertThat(config.at("/upfront/planQuotes").findValuesAsText("planId")).containsExactlyInAnyOrderElementsOf(ids(config.path("plans")));
        assertThat(today(quote(config,planId("ABONAT_FAMILIAR")))).as("50 % of the family price, 9000").isEqualTo(4500);
        assertThat(today(quote(config,planId("ABONAT")))).as("50 % of ABONAT's 6000").isEqualTo(3000);
        assertThat(config.at("/member/planId").asText()).isEqualTo(planId("ABONAT_FAMILIAR"));
        assertThat(SnapshotSchemas.violations(config,"SignupConfig")).isEmpty();
        // The public offer is unchanged: no hidden plan, and no `current` on any plan.
        var offer=anonymous("ca");
        assertThat(ids(offer.path("plans"))).containsExactlyElementsOf(planIds(OFFER));
        offer.path("plans").forEach(plan -> assertThat(plan.has("current")).as(plan.path("id").asText()).isFalse());
        // A member on an offered plan: that plan is current, and nothing is added.
        var abonat=asMember(activeMember(planId("ABONAT")));
        assertThat(ids(abonat.path("plans"))).containsExactlyElementsOf(planIds(OFFER));
        assertThat(plan(abonat,planId("ABONAT")).path("current").asBoolean()).isTrue();
        assertThat(abonat.path("plans").findValues("current").stream().filter(JsonNode::asBoolean)).hasSize(1);
        // The add-dog submission keeps the member's own plan by default (M8).
        var added=result(asMember(postJson("/me/dogs/signup",addDog("941000005900001")).header("Idempotency-Key",UUID.randomUUID()),id),201);
        var dog=mongo.getCollection("dogs").find(new Document("_id",added.path("dogId").asText()).append("clubId",club)).first();
        assertThat(dog.get("signup",Document.class).getString("planIdRequested")).isEqualTo(planId("ABONAT_FAMILIAR"));
    }

    /**
     * Step 2 (R-04-09, B34): a member without a plan (an instructor migrated without one) gets the offer, none `current`, and
     * `POST /me/dogs/signup` requires `planIdRequested`: `400 VALIDATION_ERROR` on that field, and nothing is stored.
     */
    @Test void R_04_09_aMemberWithoutAPlanGetsTheOfferAndMustChooseOne() throws Exception {
        String id=activeMember(planId("ABONAT"));
        mongo.getCollection("members").updateOne(new Document("_id",id),new Document("$unset",new Document("planId","").append("priceId","")));
        var config=asMember(id);
        assertThat(ids(config.path("plans"))).containsExactlyElementsOf(planIds(OFFER));
        config.path("plans").forEach(plan -> assertThat(plan.path("current").isBoolean()&&!plan.path("current").asBoolean()).as(plan.path("id").asText()).isTrue());
        assertThat(config.path("member").has("planId")).isFalse();
        assertThat(SnapshotSchemas.violations(config,"SignupConfig")).isEmpty();
        long dogs=mongo.getCollection("dogs").countDocuments(new Document("clubId",club).append("memberId",id));
        var refused=result(asMember(postJson("/me/dogs/signup",addDog("941000005900002")).header("Idempotency-Key",UUID.randomUUID()),id),400);
        assertThat(refused.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(refused.at("/details/fieldErrors")).hasSize(1);
        assertThat(refused.at("/details/fieldErrors/0/field").asText()).isEqualTo("planIdRequested");
        assertThat(refused.at("/details/fieldErrors/0/code").asText()).isEqualTo("REQUIRED");
        assertThat(mongo.getCollection("dogs").countDocuments(new Document("clubId",club).append("memberId",id))).isEqualTo(dogs);
        // A hidden plan that is not the member's own is still not offered; an offered one is accepted.
        var withBody=new LinkedHashMap<String,Object>(addDog("941000005900003"));withBody.put("planIdRequested",planId("ABONAT_FAMILIAR"));
        assertThat(result(asMember(postJson("/me/dogs/signup",withBody).header("Idempotency-Key",UUID.randomUUID()),id),422).path("code").asText()).isEqualTo("PLAN_NOT_AVAILABLE");
        withBody.put("planIdRequested",planId("PACK6"));
        var added=result(asMember(postJson("/me/dogs/signup",withBody).header("Idempotency-Key",UUID.randomUUID()),id),201);
        var dog=mongo.getCollection("dogs").find(new Document("_id",added.path("dogId").asText()).append("clubId",club)).first();
        assertThat(dog.get("signup",Document.class).getString("planIdRequested")).isEqualTo(planId("PACK6"));
    }

    @Autowired ClubConfigService configs;
    /** The member's add-dog without, with a gone and with an offered `planIdRequested` (R-04-09): 400 REQUIRED, 422, 201. */
    void mustChoose(String id,String gone,String offered,String chip) throws Exception {
        var config=asMember(id);
        assertThat(ids(config.path("plans"))).doesNotContain(gone);
        config.path("plans").forEach(plan -> assertThat(plan.path("current").isBoolean()&&!plan.path("current").asBoolean()).as(plan.path("id").asText()).isTrue());
        assertThat(config.at("/member/planId").asText()).isEqualTo(gone);
        assertThat(config.path("upfront").has("additionalDogOptions")).as("no options for a gone plan, as for no plan").isFalse();
        assertThat(config.at("/upfront/planQuotes").findValuesAsText("planId")).containsExactlyInAnyOrderElementsOf(ids(config.path("plans")));
        long dogs=mongo.getCollection("dogs").countDocuments(new Document("clubId",club).append("memberId",id));
        var refused=result(asMember(postJson("/me/dogs/signup",addDog(chip+"1")).header("Idempotency-Key",UUID.randomUUID()),id),400);
        assertThat(refused.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(refused.at("/details/fieldErrors")).hasSize(1);
        assertThat(refused.at("/details/fieldErrors/0/field").asText()).isEqualTo("planIdRequested");
        assertThat(refused.at("/details/fieldErrors/0/code").asText()).isEqualTo("REQUIRED");
        assertThat(mongo.getCollection("dogs").countDocuments(new Document("clubId",club).append("memberId",id))).as("nothing stored").isEqualTo(dogs);
        var withBody=new LinkedHashMap<String,Object>(addDog(chip+"2"));withBody.put("planIdRequested",gone);
        assertThat(result(asMember(postJson("/me/dogs/signup",withBody).header("Idempotency-Key",UUID.randomUUID()),id),422).path("code").asText()).isEqualTo("PLAN_NOT_AVAILABLE");
        withBody.put("planIdRequested",offered);
        var added=result(asMember(postJson("/me/dogs/signup",withBody).header("Idempotency-Key",UUID.randomUUID()),id),201);
        assertThat(mongo.getCollection("dogs").find(new Document("_id",added.path("dogId").asText()).append("clubId",club)).first().get("signup",Document.class)
                .getString("planIdRequested")).isEqualTo(offered);
    }

    /**
     * E5-T24 step 6 (R-04-09, amended 26-09; review E5-T22 #3): a member whose own plan is no longer assignable counts as a
     * member without a plan: GET /signup marks no plan current, and POST /me/dogs/signup without planIdRequested answers
     * 400 VALIDATION_ERROR with REQUIRED on that field and stores nothing. Asking for the gone plan is 422 PLAN_NOT_AVAILABLE;
     * an offered one is accepted. First the plan is deactivated (D11, PATCH /plans/{id}), then a pack's module is turned off.
     */
    @Test void R_04_09_aMemberWhoseOwnPlanIsGoneMustChooseOne() throws Exception {
        String abonat=activeMember(planId("ABONAT")),pack=activeMember(planId("PACK6"));
        long version=((Number)mongo.getCollection("plans").find(new Document("_id",planId("ABONAT"))).first().get("version")).longValue();
        result(admin(patch("/api/v1/plans/"+planId("ABONAT")).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",version,"active",false)))),200);
        mustChoose(abonat,planId("ABONAT"),planId("PACK6"),"94100000591000");
        result(admin(patch("/api/v1/plans/"+planId("ABONAT")).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",version+1,"active",true)))),200);
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$pull",new Document("modules","PACKS")));configs.invalidate(club);signupService.invalidateConfiguration(club);
        mustChoose(pack,planId("PACK6"),planId("ABONAT"),"94100000592000");
    }

    /** A submitted (PENDING) public signup of the copy; returns its member id. */
    String submitted(ObjectNode signup) throws Exception {
        String ip="198.51.100."+(++sequence%250+1);
        return result(postJson("/signup",signup).header("Idempotency-Key",UUID.randomUUID()).with(r->{r.setRemoteAddr(ip);return r;}),201).path("memberId").asText();
    }
    /** D2's validation body: the version and every pending dog with the level A. */
    Map<String,Object> validation(JsonNode review) {
        var body=new LinkedHashMap<String,Object>();body.put("version",review.path("version").asLong());
        var dogs=new ArrayList<Map<String,Object>>();for(var dog:review.path("dogs")) dogs.add(Map.of("dogId",dog.path("id").asText(),"levelId",level));body.put("dogs",dogs);
        return body;
    }
    String priceOf(String code) { return mongo.getCollection("prices").find(new Document("clubId",club).append("planId",planId(code))).first().getString("_id"); }

    /**
     * R-04-19, R-04-09, INC-39 (E11-T10): D2 loads a public signup whose requested plan was deactivated since: no plan proposal and
     * the rows as submitted. Its dry run without a plan previews them as they are, the validation without a plan still answers
     * 422 PLAN_NOT_AVAILABLE, and with an assignable plan it validates.
     */
    @Test void R_04_19_INC_39_d2LoadsASignupWhoseRequestedPlanIsGoneAndValidatesItOnceAPlanIsChosen() throws Exception {
        String id=submitted(request(planId("ABONAT")));
        long version=((Number)mongo.getCollection("plans").find(new Document("_id",planId("ABONAT"))).first().get("version")).longValue();
        result(admin(patch("/api/v1/plans/"+planId("ABONAT")).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",version,"active",false)))),200);
        var review=result(admin(get("/api/v1/members/"+id+"/signup")),200);
        assertThat(review.at("/proposals/planId").asText(null)).isNull();assertThat(review.at("/proposals/priceId").asText(null)).isNull();
        assertThat(review.at("/signup/planIdRequested").asText()).isEqualTo(planId("ABONAT"));
        assertThat(review.path("planOptions").findValuesAsText("planId")).doesNotContain(planId("ABONAT")).contains(planId("PACK6"));
        assertThat(review.at("/upfront/lines")).hasSize(2);assertThat(review.at("/upfront/totalDue/amountMinor").asLong()).isPositive();
        assertThat(SnapshotSchemas.violations(review,"MemberSignupView")).isEmpty();
        var body=validation(review);
        var dry=result(admin(postJson("/members/"+id+"/validation",body).param("dryRun","true")),200);
        assertThat(dry.at("/upfront/totalDue")).isEqualTo(review.at("/upfront/totalDue"));assertThat(dry.at("/upfront/lines").findValuesAsText("id")).isEqualTo(review.at("/upfront/lines").findValuesAsText("id"));
        assertThat(dry.path("price").asText(null)).isNull();
        body.put("upfrontAmountPaid",Map.of("amountMinor",0,"currency","EUR"));
        assertThat(result(admin(postJson("/members/"+id+"/validation",body)),422).path("code").asText()).isEqualTo("PLAN_NOT_AVAILABLE");
        assertThat(member(id).getString("status")).isEqualTo("PENDING");
        body.put("planId",planId("PACK6"));body.remove("upfrontAmountPaid");
        var chosen=result(admin(postJson("/members/"+id+"/validation",body).param("dryRun","true")),200);
        assertThat(chosen.at("/price/id").asText()).isEqualTo(priceOf("PACK6"));
        body.put("upfrontAmountPaid",mapper.convertValue(chosen.at("/upfront/totalDue"),Map.class));
        result(admin(postJson("/members/"+id+"/validation",body)),200);
        assertThat(member(id).getString("status")).isEqualTo("ACTIVE");assertThat(member(id).getString("planId")).isEqualTo(planId("PACK6"));
    }

    /**
     * Ruling E138 (E11-T13, INC-74): with a legacy start twin (two current MONTHLY_FEE prices of ABONAT, INC-68), ABONAT leaves the
     * public signup offer (`GET /signup`, and a new submission that requests it), and D2 refuses to validate a submission on it,
     * dry run included, with billing's `409 PRICE_OVERLAP {planId, concept, priceId, conflictingPriceId}`; nothing is written. The
     * admin's list keeps the plan with both prices, and once the twin is resolved the same submission validates.
     */
    @Test void E138_aPlanWithAPriceTwinLeavesTheSignupOfferAndD2RefusesItsValidation() throws Exception {
        assertThat(ids(anonymous("ca").path("plans"))).contains(planId("ABONAT"));
        String id=submitted(request(planId("ABONAT")));
        var review=result(admin(get("/api/v1/members/"+id+"/signup")),200);
        String priceId=review.at("/proposals/priceId").asText();
        var body=validation(review);body.put("nextInvoiceDate",review.at("/proposals/nextInvoiceDate").asText());
        // The twin as INC-68 left it: ABONAT's current monthly price again, with its start stored as a BSON date of the same day.
        var twin=new Document(mongo.getCollection("prices").find(new Document("_id",priceId).append("clubId",club)).first());
        twin.put("_id","e138-twin");twin.put("validFrom",Date.from(java.time.LocalDate.parse(twin.getString("validFrom")).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        twin.put("amount",new Document("amountMinor",twin.get("amount",Document.class).get("amountMinor",Number.class).longValue()+500).append("currency","EUR"));
        mongo.getCollection("prices").insertOne(twin);

        assertThat(ids(anonymous("ca").path("plans"))).doesNotContain(planId("ABONAT")).contains(planId("PACK6"));
        assertThat(result(postJson("/signup",request(planId("ABONAT"))).header("Idempotency-Key",UUID.randomUUID()).with(r->{r.setRemoteAddr("198.51.100.252");return r;}),422)
                .path("code").asText()).isEqualTo("PLAN_NOT_AVAILABLE");
        for(String dryRun:List.of("true","false")) {
            var refused=result(admin(postJson("/members/"+id+"/validation",body).param("dryRun",dryRun)),409);
            assertThat(refused.path("code").asText()).as(dryRun).isEqualTo("PRICE_OVERLAP");
            assertThat(refused.at("/details/planId").asText()).as(dryRun).isEqualTo(planId("ABONAT"));
            assertThat(refused.at("/details/concept").asText()).as(dryRun).isEqualTo("MONTHLY_FEE");
            assertThat(List.of(refused.at("/details/priceId").asText(),refused.at("/details/conflictingPriceId").asText())).as(dryRun).containsExactlyInAnyOrder(priceId,"e138-twin");
        }
        assertThat(member(id).getString("status")).isEqualTo("PENDING");assertThat(member(id).getString("priceId")).isNull();
        JsonNode row=null;for(var item:result(admin(get("/api/v1/plans")),200).path("items")) if(item.path("id").asText().equals(planId("ABONAT"))) row=item;
        assertThat(row).as("the admin's list keeps ABONAT").isNotNull();
        assertThat(row.path("currentPrices").findValuesAsText("id")).containsExactlyInAnyOrder(priceId,"e138-twin");

        mongo.getCollection("prices").deleteOne(new Document("_id","e138-twin").append("clubId",club));
        var dry=result(admin(postJson("/members/"+id+"/validation",body).param("dryRun","true")),200);
        assertThat(dry.at("/price/id").asText()).isEqualTo(priceId);
        body.put("upfrontAmountPaid",mapper.convertValue(dry.at("/upfront/totalDue"),Map.class));
        result(admin(postJson("/members/"+id+"/validation",body)),200);
        assertThat(member(id).getString("status")).isEqualTo("ACTIVE");assertThat(member(id).getString("priceId")).isEqualTo(priceId);
        assertThat(ids(anonymous("ca").path("plans"))).contains(planId("ABONAT"));
    }

    /** INC-68's twin of {@code code}'s current price, as in the test above: a copy starting the same day (a BSON date), 5 € dearer. */
    String twin(String code) {
        var twin=new Document(mongo.getCollection("prices").find(new Document("_id",priceOf(code)).append("clubId",club)).first());
        String id="e138-twin-"+code.toLowerCase(Locale.ROOT)+"-"+UUID.randomUUID();twin.put("_id",id);
        twin.put("validFrom",Date.from(java.time.LocalDate.parse(twin.getString("validFrom")).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        twin.put("amount",new Document("amountMinor",twin.get("amount",Document.class).get("amountMinor",Number.class).longValue()+500).append("currency","EUR"));
        mongo.getCollection("prices").insertOne(twin);return id;
    }
    /** The 409 PRICE_OVERLAP of {@code code}'s twin: billing's four details, the two prices in either order. */
    void priceOverlap(JsonNode refused,String code,String priceId,String twinId) {
        assertThat(refused.path("code").asText()).isEqualTo("PRICE_OVERLAP");
        assertThat(refused.at("/details/planId").asText()).isEqualTo(planId(code));
        assertThat(refused.at("/details/concept").asText()).isEqualTo("MONTHLY_FEE");
        assertThat(List.of(refused.at("/details/priceId").asText(),refused.at("/details/conflictingPriceId").asText())).containsExactlyInAnyOrder(priceId,twinId);
    }
    /** What an add-dog writes for {@code id}: its dogs, its upfront payment rows and its SignupSubmitted events. */
    List<Long> written(String id) {
        return List.of(mongo.getCollection("dogs").countDocuments(new Document("clubId",club).append("memberId",id)),
                mongo.getCollection("upfront_payments").countDocuments(new Document("clubId",club).append("memberId",id)),
                mongo.getCollection("domain_events").countDocuments(new Document("type","SignupSubmitted").append("payload.memberId",id)));
    }

    /**
     * R-04-13/14, ruling E138 (E11-T13 round 2, item 1): a twin on the family fare (ABONAT_FAMILIAR, 2 dogs, +5 €) reaches no
     * member-facing amount. The public texts carry no family intro (null, as without a family fare); a member on the 1-dog ABONAT,
     * whose added dog's fee is the family fare's delta, gets no additionalDogOptions and no ABONAT quote, and its add-dog on ABONAT
     * answers 409 PRICE_OVERLAP on the family fare with nothing written. A pack, whose quote reads no twin, is still quoted and taken.
     */
    @Test void R_04_13_E138_aTwinOnTheFamilyFareReachesNoFamilyAmountNorAddDogQuote() throws Exception {
        String id=activeMember(planId("ABONAT"));
        assertThat(anonymous("ca").at("/texts/familyGroupIntro").asText()).contains("90");
        var before=asMember(id);
        assertThat(before.at("/upfront/additionalDogOptions")).isNotEmpty();
        assertThat(before.at("/upfront/planQuotes").findValuesAsText("planId")).contains(planId("ABONAT"));
        String familyPrice=priceOf("ABONAT_FAMILIAR"),twin=twin("ABONAT_FAMILIAR");

        var texts=anonymous("ca").path("texts");
        assertThat(texts.has("familyGroupIntro")).isTrue();assertThat(texts.path("familyGroupIntro").isNull()).as("no amount from one of two twins").isTrue();
        var config=asMember(id);
        assertThat(config.path("upfront").has("additionalDogOptions")).isFalse();
        assertThat(config.at("/upfront/planQuotes").findValuesAsText("planId")).containsExactlyInAnyOrderElementsOf(planIds(List.of("PACK6","PACK10","TERAPIA")));
        assertThat(plan(config,planId("ABONAT")).path("current").asBoolean()).as("its own plan has no twin").isTrue();
        assertThat(SnapshotSchemas.violations(config,"SignupConfig")).isEmpty();
        var nothing=written(id);
        priceOverlap(result(asMember(postJson("/me/dogs/signup",addDog("941000005940001")).header("Idempotency-Key",UUID.randomUUID()),id),409),"ABONAT_FAMILIAR",familyPrice,twin);
        var named=new LinkedHashMap<String,Object>(addDog("941000005940002"));named.put("planIdRequested",planId("ABONAT"));
        priceOverlap(result(asMember(postJson("/me/dogs/signup",named).header("Idempotency-Key",UUID.randomUUID()),id),409),"ABONAT_FAMILIAR",familyPrice,twin);
        assertThat(written(id)).as("no dog, no upfront payment, no SignupSubmitted").isEqualTo(nothing);
        named.put("planIdRequested",planId("PACK6"));
        result(asMember(postJson("/me/dogs/signup",named).header("Idempotency-Key",UUID.randomUUID()),id),201);
    }

    /**
     * R-04-09, ruling E138 (E11-T13 round 2, item 2): a member whose own plan has a price twin is a member whose plan can no longer
     * be assigned (B34): GET /signup lists the offer with no current plan, no additionalDogOptions and no quote of it; the add-dog
     * on it answers 409 PRICE_OVERLAP and without a plan 400 planIdRequested REQUIRED, nothing written. D2 still loads the dog it
     * had added before the twin, and its dry run and validation answer 409 PRICE_OVERLAP. The admin's list keeps both prices.
     */
    @Test void R_04_09_E138_aMemberWhoseOwnPlanIsTwinnedChoosesFromTheOfferAndD2RefusesItsPendingDog() throws Exception {
        String id=activeMember(planId("ABONAT"));
        result(asMember(postJson("/me/dogs/signup",addDog("941000005950001")).header("Idempotency-Key",UUID.randomUUID()),id),201);
        String price=priceOf("ABONAT"),twin=twin("ABONAT");

        var config=asMember(id);
        assertThat(ids(config.path("plans"))).containsExactlyElementsOf(planIds(List.of("PACK6","PACK10","TERAPIA")));
        config.path("plans").forEach(plan -> assertThat(plan.path("current").isBoolean()&&!plan.path("current").asBoolean()).as(plan.path("id").asText()).isTrue());
        assertThat(config.path("upfront").has("additionalDogOptions")).isFalse();
        assertThat(config.at("/upfront/planQuotes").findValuesAsText("planId")).containsExactlyInAnyOrderElementsOf(ids(config.path("plans")));
        assertThat(config.at("/member/planId").asText()).isEqualTo(planId("ABONAT"));
        assertThat(SnapshotSchemas.violations(config,"SignupConfig")).isEmpty();
        var nothing=written(id);
        var named=new LinkedHashMap<String,Object>(addDog("941000005950002"));named.put("planIdRequested",planId("ABONAT"));
        priceOverlap(result(asMember(postJson("/me/dogs/signup",named).header("Idempotency-Key",UUID.randomUUID()),id),409),"ABONAT",price,twin);
        var required=result(asMember(postJson("/me/dogs/signup",addDog("941000005950003")).header("Idempotency-Key",UUID.randomUUID()),id),400);
        assertThat(required.at("/details/fieldErrors/0/field").asText()).isEqualTo("planIdRequested");
        assertThat(required.at("/details/fieldErrors/0/code").asText()).isEqualTo("REQUIRED");
        assertThat(written(id)).as("no dog, no upfront payment, no SignupSubmitted").isEqualTo(nothing);

        var review=result(admin(get("/api/v1/members/"+id+"/signup")),200);
        var body=validation(review);
        for(String dryRun:List.of("true","false")) priceOverlap(result(admin(postJson("/members/"+id+"/validation",body).param("dryRun",dryRun)),409),"ABONAT",price,twin);
        assertThat(mongo.getCollection("dogs").countDocuments(new Document("clubId",club).append("memberId",id).append("status","PENDING"))).isEqualTo(1);
        JsonNode row=null;for(var item:result(admin(get("/api/v1/plans")),200).path("items")) if(item.path("id").asText().equals(planId("ABONAT"))) row=item;
        assertThat(row).as("the admin's list keeps ABONAT").isNotNull();
        assertThat(row.path("currentPrices").findValuesAsText("id")).containsExactlyInAnyOrder(price,twin);
    }

    /**
     * R-04-13, ruling E138 (E11-T13 round 2, item 3): D2 naming either twin's priceId answers the same 409 PRICE_OVERLAP, dry run
     * included, before the priceId is compared with the plan's billed price.
     */
    @Test void R_04_13_E138_d2RefusesEitherTwinsPriceIdWithTheSameDetails() throws Exception {
        String id=submitted(request(planId("ABONAT")));
        var review=result(admin(get("/api/v1/members/"+id+"/signup")),200);
        String price=priceOf("ABONAT"),twin=twin("ABONAT");
        var body=validation(review);body.put("nextInvoiceDate",review.at("/proposals/nextInvoiceDate").asText());body.put("planId",planId("ABONAT"));
        for(String chosen:List.of(price,twin)) for(String dryRun:List.of("true","false")) {
            body.put("priceId",chosen);
            priceOverlap(result(admin(postJson("/members/"+id+"/validation",body).param("dryRun",dryRun)),409),"ABONAT",price,twin);
        }
        assertThat(member(id).getString("status")).isEqualTo("PENDING");
    }

    /**
     * R-04-13, T-04-16, INC-39 (E11-T10): the family-fare proposal counts the dogs of the group the validation leaves the member in,
     * every member's. An applicant without a claim whom D2 puts in a group (`familyGroupId`) with two dogs gets the largest
     * family fare (no plan includes three); a claim on the holder of that group counts three and, once a fare includes three,
     * gets that one.
     */
    @Test void R_04_13_T_04_16_INC_39_theFamilyFareCountsEveryDogOfTheResultingGroup() throws Exception {
        String family=familyMember();String group=member(family).getString("familyGroupId");
        String holder=mongo.getCollection("family_groups").find(new Document("_id",group).append("clubId",club)).first().getString("holderMemberId");
        String alone=submitted(request(planId("ABONAT")));
        var body=validation(result(admin(get("/api/v1/members/"+alone+"/signup")),200));body.put("familyGroupId",group);
        var dry=result(admin(postJson("/members/"+alone+"/validation",body).param("dryRun","true")),200);
        assertThat(dry.at("/price/id").asText()).as("1 + the group's 2 dogs: the largest family fare").isEqualTo(priceOf("ABONAT_FAMILIAR"));
        // A family fare that includes three dogs (fictional, on the copy).
        var familiar=mongo.getCollection("plans").find(new Document("_id",planId("ABONAT_FAMILIAR"))).first();
        String three=UUID.randomUUID().toString();var plan=new Document(familiar);plan.put("_id",three);plan.put("code","ABONAT_FAMILIAR_3");plan.put("dogsIncluded",3);
        mongo.getCollection("plans").insertOne(plan);
        var price=new Document(mongo.getCollection("prices").find(new Document("_id",priceOf("ABONAT_FAMILIAR"))).first());
        price.put("_id",UUID.randomUUID().toString());price.put("planId",three);price.put("amount",new Document("amountMinor",12000L).append("currency","EUR"));
        mongo.getCollection("prices").insertOne(price);
        var holderRow=member(holder);String holderDog=mongo.getCollection("dogs").find(new Document("memberId",holder).append("clubId",club)).first().getString("name");
        var claim=request(planId("ABONAT"));
        claim.set("familyGroupClaim",mapper.valueToTree(Map.of("holderName",holderRow.getString("firstName")+" "+holderRow.getString("lastName1"),"dogName",holderDog,"leavePending",false)));
        String claimed=submitted(claim);
        assertThat(result(admin(get("/api/v1/members/"+claimed+"/signup")),200).at("/proposals/planId").asText()).as("the holder's group has 2 dogs: 3 with the applicant's").isEqualTo(three);
        assertThat(result(admin(postJson("/members/"+alone+"/validation",body).param("dryRun","true")),200).at("/price/id").asText()).isEqualTo(price.getString("_id"));
        // E11-T10 review. Each dog once, never an INACTIVE one: with the family member's dog gone (INACTIVE) the group adds the
        // holder's dog alone, and the applicant's pending dog (selected, and one of its own dogs) counts once: 2 dogs.
        String familyDog=mongo.getCollection("dogs").find(new Document("memberId",family).append("clubId",club)).first().getString("_id");
        mongo.getCollection("dogs").updateOne(new Document("_id",familyDog),new Document("$set",new Document("status","INACTIVE")));
        assertThat(result(admin(postJson("/members/"+alone+"/validation",body).param("dryRun","true")),200).at("/price/id").asText())
                .as("1 pending + the holder's: 2 dogs, the fare that includes two").isEqualTo(priceOf("ABONAT_FAMILIAR"));
        assertThat(result(admin(get("/api/v1/members/"+claimed+"/signup")),200).at("/proposals/planId").asText()).isEqualTo(planId("ABONAT_FAMILIAR"));
        // A group that is not ACTIVE adds nobody: neither through D2's familyGroupId nor through the claimed holder who is in it.
        var withoutGroup=new java.util.LinkedHashMap<>(body);withoutGroup.remove("familyGroupId");
        String standard=result(admin(postJson("/members/"+alone+"/validation",withoutGroup).param("dryRun","true")),200).at("/price/id").asText();
        assertThat(standard).isNotEqualTo(priceOf("ABONAT_FAMILIAR"));
        mongo.getCollection("family_groups").updateOne(new Document("_id",group),new Document("$set",new Document("status","DISSOLVED")));
        assertThat(result(admin(postJson("/members/"+alone+"/validation",body).param("dryRun","true")),200).at("/price/id").asText()).isEqualTo(standard);
        assertThat(result(admin(get("/api/v1/members/"+claimed+"/signup")),200).at("/proposals/planId").asText()).isEqualTo(planId("ABONAT"));
    }
}

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
import org.springframework.boot.test.autoconfigure.web.servlet.*;
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
    String activeMember(String planId) throws Exception {
        String ip="198.51.100."+(++sequence%250+1);
        String id=result(postJson("/signup",request(planId)).header("Idempotency-Key",UUID.randomUUID()).with(r->{r.setRemoteAddr(ip);return r;}),201).path("memberId").asText();
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

    /** Step 1 (E4-W12 question 1): every plan with `texts.priceLabel` carries it in the reader's locale; the others have none. */
    @Test void R_05_19_T_04_09_eachPlanCarriesItsPriceLabelInTheReadersLocale() throws Exception {
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
     */
    @Test void R_04_09_T_04_21_theFamilyMembersOwnHiddenPlanIsListedAsCurrentInAddDogMode() throws Exception {
        String id=activeMember(planId("ABONAT"));
        mongo.getCollection("members").updateOne(new Document("_id",id),new Document("$set",new Document("planId",planId("ABONAT_FAMILIAR"))));
        var config=asMember(id);
        assertThat(ids(config.path("plans"))).containsExactlyElementsOf(planIds(List.of("ABONAT","ABONAT_FAMILIAR","PACK6","PACK10","TERAPIA")));
        var family=plan(config,planId("ABONAT_FAMILIAR"));
        assertThat(family.path("current").asBoolean()).isTrue();
        assertThat(family.path("name").asText()).isEqualTo("Abonat · 2 gossos (familiar)");
        assertThat(family.at("/price/amount/amountMinor").asLong()).isEqualTo(9000);
        for(var plan:config.path("plans")) if(plan!=family) assertThat(plan.path("current").isBoolean()&&!plan.path("current").asBoolean()).as(plan.path("id").asText()).isTrue();
        // One quote per listed plan, the family fare included (M5).
        assertThat(config.at("/upfront/planQuotes").findValuesAsText("planId")).containsExactlyInAnyOrderElementsOf(ids(config.path("plans")));
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
}

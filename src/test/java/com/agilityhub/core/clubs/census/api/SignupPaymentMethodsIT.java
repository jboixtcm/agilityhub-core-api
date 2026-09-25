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
    String club,host,plan;
    int sequence;
    @BeforeEach void fixtureClub() {
        club="pm-"+UUID.randomUUID();host=club+".example.test";plan=UUID.randomUUID().toString();
        ObjectNode tree=mapper.valueToTree(PlatformFixtures.club(club,host));tree.set("modules",mapper.valueToTree(Module.values()));
        tree.set("paymentProviders",mapper.valueToTree(Map.of("MANUAL",Map.of("enabled",true),"SEPA_XML",Map.of("enabled",true))));
        clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(club);hosts.invalidate();
        var name=new LocalizedText(Map.of("ca","Example","es","Example","en","Example"),"en");
        mongo.insert(new Plan(plan,club,"MONTHLY",name,PlanType.MONTHLY,BillingMode.MONTHLY_FEE,1,new EntryFee(EntryFeeMode.STANDARD,null,null),null,null,name,null,true,true,0,true,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Price(UUID.randomUUID().toString(),club,plan,PriceConcept.MONTHLY_FEE,new Money(6000,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2020,1,1),null,0,clock.instant(),clock.instant(),null,null));
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
    @Test void R_04_19_theD2ViewPublishesTheAssignableMethodsAndAlwaysTheApplicantsCurrentOne() throws Exception {
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
    @Test void R_04_10_withoutBillingTheD2ViewHasNoPaymentMethods() throws Exception {
        String id=submit(request("MANUAL"),201).path("memberId").asText();
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("modules",List.of())));
        configs.invalidate(club);
        var view=review(id,"ca");
        assertThat(view.path("paymentMethods").isArray()).isTrue();assertThat(view.path("paymentMethods")).isEmpty();
    }
}

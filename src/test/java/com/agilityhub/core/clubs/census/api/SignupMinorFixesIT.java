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
 * E3-T10: the api minors of the gate E3 audit (`roadmap/reviews/gate-E3/consolidated.md`, «Minor», api) and the three
 * narrow cases of the E3-T08 round-2 review (step 15). Same Spring context as {@link SignupIT}; one fixture club with
 * one 60 € monthly plan and every module.
 */
@org.springframework.boot.test.context.SpringBootTest(properties={"shared.scheduling.enabled=false","core.security.rate-limits.enabled=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class SignupMinorFixesIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired MongoTemplate mongo;@Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;@Autowired HostTenantResolver hosts;
    @Autowired com.agilityhub.core.clubs.census.application.SignupService signupService;
    @Autowired com.agilityhub.core.shared.application.OutboxDispatcher dispatcher;
    @Autowired com.agilityhub.core.clubs.messaging.application.EmailSender sender;
    @Autowired com.agilityhub.core.payments.application.FakeCheckoutGateway fake;
    String club,host,plan,level;
    int sequence;
    @BeforeEach void fixtureClub() {
        club="mf-"+UUID.randomUUID();host=club+".example.test";plan=UUID.randomUUID().toString();level=UUID.randomUUID().toString();
        ObjectNode tree=mapper.valueToTree(PlatformFixtures.club(club,host));tree.set("modules",mapper.valueToTree(Module.values()));
        tree.set("paymentProviders",mapper.valueToTree(Map.of("MANUAL",Map.of("enabled",true),"SEPA_XML",Map.of("enabled",true))));
        clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(club);hosts.invalidate();
        plan("MONTHLY",plan,new LocalizedText(Map.of("ca","Example","es","Example","en","Example"),"en"));
        // A complete level: D1's risk card maps the club's levels to entities (a bare name map is not a LocalizedText).
        mongo.insert(new Level(level,club,"A",new LocalizedText(Map.of("ca","Iniciació","es","Iniciación","en","Beginner"),"en"),0,"#000000",8,false,true,true,0,clock.instant(),clock.instant(),null,null));
        mailbox().clear();
    }
    void plan(String code,String id,LocalizedText name) {
        mongo.insert(new Plan(id,club,code,name,PlanType.MONTHLY,BillingMode.MONTHLY_FEE,1,new EntryFee(EntryFeeMode.STANDARD,null,null),null,null,name,null,true,true,0,true,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Price(UUID.randomUUID().toString(),club,id,PriceConcept.MONTHLY_FEE,new Money(6000,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2020,1,1),null,0,clock.instant(),clock.instant(),null,null));
        signupService.invalidateConfiguration(club);
    }
    ObjectNode request() {
        int value=14000000+(++sequence);String national=String.format("%08d",value)+"TRWAGMYFPDXBNJZSQVHLCKE".charAt(value%23);
        return mapper.valueToTree(Map.of("locale","ca","website","","person",Map.of("idDocument",Map.of("type","DNI","value",national),"firstName","Example","lastName1","Applicant"+sequence,"birthDate","2000-01-01","gender","FEMALE","emails",List.of("minor"+sequence+"."+club.substring(3,11)+"@example.test"),"phones",List.of(Map.of("prefix","+34","number","600000001")),"address",Map.of("street","Example street","postalCode","99999","town","Example town")),
                "dog",Map.of("name","Example Dog "+sequence,"sex","FEMALE","breed","Example breed","birthMonth","2024-04","chip","941000002"+String.format("%06d",sequence)),"planId",plan,
                "payment",Map.of("type","MANUAL","firstMonthOption","TODAY"),"consents",Map.of("privacyPolicy",Map.of("accepted",true,"version","v1"),"imageUse",Map.of("granted",false,"version","v1"))));
    }
    MockHttpServletRequestBuilder postJson(String path,Object body) throws Exception {return post("/api/v1"+path).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));}
    MockHttpServletRequestBuilder patchJson(String path,Object body) throws Exception {return patch("/api/v1"+path).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));}
    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) { return request.header("Host",host).with(jwt().jwt(j->j.subject("minor-admin").claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))); }
    MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request,String id) {
        String account=member(id).getString("accountId");return request.header("Host",host).with(jwt().jwt(j->j.subject(account).claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_MEMBER")));
    }
    JsonNode result(MockHttpServletRequestBuilder request,int status) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();var body=mapper.readTree(response.getContentAsString().isEmpty()?"{}":response.getContentAsString());
        assertThat(response.getStatus()).as("HTTP code=%s details=%s",body.path("code").asText(),body.path("details")).isEqualTo(status);return body;
    }
    /** Each submission comes from its own fictional address: the R-04-20 limits are per club and IP. */
    JsonNode submit(Object body,int status) throws Exception {
        String ip="192.0.2."+(++sequence%250+1);
        return result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()).header("User-Agent","Fictional agent/1.0").with(r->{r.setRemoteAddr(ip);return r;}),status);
    }
    JsonNode submit(Object body) throws Exception { return submit(body,201); }
    Document member(String id) {return mongo.getCollection("members").find(new Document("_id",id).append("clubId",club)).first();}
    Document dogDocument(String id) {return mongo.getCollection("dogs").find(new Document("_id",id).append("clubId",club)).first();}
    List<Document> collection(String name) {return mongo.getCollection(name).find(new Document("clubId",club)).into(new ArrayList<>());}
    List<Document> rows(String memberId) {return mongo.getCollection("upfront_payments").find(new Document("clubId",club).append("memberId",memberId)).into(new ArrayList<>());}
    List<Document> events(String type) { return collection("domain_events").stream().filter(e -> type.equals(e.getString("type"))).toList(); }
    String pendingDog(String id) {return collection("dogs").stream().filter(d->id.equals(d.getString("memberId"))&&"PENDING".equals(d.getString("status"))).findFirst().orElseThrow().getString("_id");}
    JsonNode review(String id) throws Exception { return result(admin(get("/api/v1/members/"+id+"/signup")),200); }
    /** Validates the given pending dogs (all of them when empty) with the D2 proposals, paying `paid` or the dry-run total. */
    JsonNode validate(String id,List<String> dogIds,Long paid) throws Exception {
        var review=review(id);var body=new LinkedHashMap<String,Object>();body.put("version",review.path("version").asLong());
        var dogs=new ArrayList<Map<String,Object>>();for(var dog:review.path("dogs")) if(dogIds.isEmpty()||dogIds.contains(dog.path("id").asText())) dogs.add(Map.of("dogId",dog.path("id").asText(),"levelId",level));body.put("dogs",dogs);
        if("PENDING".equals(member(id).getString("status"))) body.put("nextInvoiceDate",review.at("/proposals/nextInvoiceDate").asText());
        var dry=result(admin(postJson("/members/"+id+"/validation",body).param("dryRun","true")),200);
        body.put("upfrontAmountPaid",paid==null?mapper.convertValue(dry.at("/upfront/totalDue"),Map.class):Map.of("amountMinor",paid,"currency","EUR"));
        return result(admin(postJson("/members/"+id+"/validation",body)),200);
    }
    JsonNode validate(String id) throws Exception { return validate(id,List.of(),null); }
    JsonNode reject(String id,String reason) throws Exception { return result(admin(postJson("/members/"+id+"/rejection",Map.of("version",member(id).get("version"),"reason",reason))),200); }
    String activeMember() throws Exception { String id=submit(request()).path("memberId").asText();validate(id);return id; }
    Map<String,Object> addDog(String chip,String planId) {
        var body=new LinkedHashMap<String,Object>(Map.of("dog",Map.of("name","Added Dog "+chip.substring(chip.length()-3),"sex","MALE","breed","Example breed","birthMonth","2023-02","chip",chip),"documents",List.of()));
        if(planId!=null) body.put("planIdRequested",planId);return body;
    }
    String addDog(String id,String chip,String planId,String language) throws Exception {
        return result(asMember(postJson("/me/dogs/signup",addDog(chip,planId)).header("Idempotency-Key",UUID.randomUUID()).header("Accept-Language",language).header("User-Agent","Fictional app/2.0"),id),201).path("dogId").asText();
    }
    void dispatch() {
        for(int i=0;i<12;i++) dispatcher.dispatch();
        assertThat(collection("domain_events").stream().filter(e->!"PUBLISHED".equals(e.getString("status"))).map(e->e.getString("type")+":"+e.getString("lastError"))).isEmpty();
    }
    com.agilityhub.core.clubs.messaging.application.FakeEmailSender mailbox() { return (com.agilityhub.core.clubs.messaging.application.FakeEmailSender)sender; }
    void parameter(String key,Object value) {
        mongo.remove(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is(club).and("key").is(key)),Parameter.class);
        mongo.insert(new Parameter(UUID.randomUUID().toString(),club,key,value,"unknown","club",null,List.of(),0L,clock.instant()));configs.invalidate(club);signupService.invalidateConfiguration(club);
    }
    void stripe() {
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("paymentProviders",new Document("MANUAL",new Document("enabled",true)).append("SEPA_XML",new Document("enabled",true)).append("STRIPE",new Document("enabled",true)))));
        configs.invalidate(club);signupService.invalidateConfiguration(club);
    }
    private String administrator(String language) {
        String id=UUID.randomUUID().toString();
        mongo.insert(new com.agilityhub.core.identity.persistence.Account(id,id+"@example.test","Example Admin",language,null,Set.of(),com.agilityhub.core.identity.persistence.Account.Status.ACTIVE,null,Map.of(),false,clock.instant()));
        mongo.insert(new com.agilityhub.core.identity.persistence.Membership(id,id,club,null,Set.of(com.agilityhub.core.identity.domain.Role.ADMIN),com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE,com.agilityhub.core.identity.domain.Role.ADMIN));
        return id;
    }
    static long minor(JsonNode money) { return money.path("amountMinor").asLong(); }

    // ---- Step 1 (R-04-12): a FOUND claim that is no longer unique at submission ----
    @Test void R_04_12_T_04_16_aFoundClaimNoLongerUniqueAtSubmissionFallsBackToNotFoundPending() throws Exception {
        var holder=request();((ObjectNode)holder.get("person")).put("firstName","Holder").put("lastName1","Example");((ObjectNode)holder.get("dog")).put("name","Kiwi");
        String holderId=submit(holder).path("memberId").asText();
        assertThat(result(postJson("/signup/family-group-lookups",Map.of("holderName","Holder Example","dogName","Kiwi")),200).path("result").asText()).isEqualTo("FOUND");
        // Between the lookup and the submission a second «Holder Example» with a «Kiwi» makes the match ambiguous.
        var twin=request();((ObjectNode)twin.get("person")).put("firstName","Holder").put("lastName1","Example");((ObjectNode)twin.get("dog")).put("name","kiwi");submit(twin);
        var body=request();body.set("familyGroupClaim",mapper.valueToTree(Map.of("holderName","Holder Example","dogName","Kiwi","leavePending",false)));
        String id=submit(body).path("memberId").asText();
        var claim=member(id).get("familyGroupClaim",Document.class);
        assertThat(claim.getString("status")).isEqualTo("NOT_FOUND_PENDING");assertThat(claim.containsKey("holderMemberId")).isFalse();
        assertThat(claim.getString("holderName")).isEqualTo("Holder Example");assertThat(claim.getString("dogName")).isEqualTo("Kiwi");
        assertThat(review(id).path("warnings").toString()).contains("FAMILY_HOLDER_NOT_FOUND");
        // A holder who is no longer eligible (the dog left) falls back the same way.
        mongo.getCollection("dogs").updateMany(new Document("clubId",club).append("name",new Document("$in",List.of("kiwi"))),new Document("$set",new Document("status","INACTIVE")));
        assertThat(result(postJson("/signup/family-group-lookups",Map.of("holderName","Holder Example","dogName","Kiwi")),200).path("result").asText()).isEqualTo("FOUND");
        mongo.getCollection("dogs").updateMany(new Document("clubId",club).append("memberId",holderId),new Document("$set",new Document("status","INACTIVE")));
        var gone=request();gone.set("familyGroupClaim",mapper.valueToTree(Map.of("holderName","Holder Example","dogName","Kiwi","leavePending",false)));
        assertThat(member(submit(gone).path("memberId").asText()).get("familyGroupClaim",Document.class).getString("status")).isEqualTo("NOT_FOUND_PENDING");
        // Without `signup.allowFamilyGroupPending` the applicant must fix the claim: 422 FAMILY_HOLDER_NOT_FOUND, nothing stored.
        parameter("signup.allowFamilyGroupPending",false);long members=collection("members").size();
        var refused=request();refused.set("familyGroupClaim",mapper.valueToTree(Map.of("holderName","Holder Example","dogName","Kiwi","leavePending",false)));
        assertThat(submit(refused,422).path("code").asText()).isEqualTo("FAMILY_HOLDER_NOT_FOUND");assertThat(collection("members")).hasSize((int)members);
    }

    // ---- Step 2 (R-04-18): a migrated SEPA member has `ibanLast4` and no full IBAN ----
    @Test void R_04_18_T_04_14_migratedSepaMembersWithIbanLast4AreNotAccountNotProvided() throws Exception {
        String migrated=activeMember(),missing=activeMember();
        // The migration stores the IBAN encrypted with its last four digits (MigrationApplyService); the ciphertext is fictional.
        mongo.getCollection("members").updateOne(new Document("_id",migrated),new Document("$set",new Document("paymentMethod",
                new Document("type","SEPA_DD").append("ibanEncrypted","fictional-ciphertext").append("ibanLast4","1332").append("holderName","Example Holder").append("mandateRef","AH-"+migrated))));
        mongo.getCollection("members").updateOne(new Document("_id",missing),new Document("$set",new Document("paymentMethod",new Document("type","SEPA_DD").append("holderName","Example Holder"))));
        addDog(migrated,"941000003000001",null,"ca");addDog(missing,"941000003000002",null,"ca");
        assertThat(review(migrated).path("warnings").toString()).doesNotContain("ACCOUNT_NOT_PROVIDED");
        assertThat(review(missing).path("warnings").toString()).contains("ACCOUNT_NOT_PROVIDED");
        var list=result(admin(get("/api/v1/members").param("filter","signupPending:eq:true")),200).path("items");
        var byId=new HashMap<String,JsonNode>();for(var item:list) byId.put(item.path("id").asText(),item);
        assertThat(byId.get(migrated).path("warnings").toString()).doesNotContain("ACCOUNT_NOT_PROVIDED");
        assertThat(byId.get(missing).path("warnings").toString()).contains("ACCOUNT_NOT_PROVIDED");
        var d1=new HashMap<String,JsonNode>();for(var item:result(admin(get("/api/v1/dashboard")),200).at("/pendingSignups/items")) d1.put(item.path("memberId").asText(),item);
        assertThat(d1.get(migrated).path("warnings").toString()).doesNotContain("ACCOUNT_NOT_PROVIDED");
        assertThat(d1.get(missing).path("warnings").toString()).contains("ACCOUNT_NOT_PROVIDED");
    }

    // ---- Step 3 (R-04-19): the D2 paymentMethod PATCH is partial ----
    @Test void R_04_19_T_04_20_paymentPatchKeepsTheHolderTaxIdIbanAndMandateDateUnlessChanged() throws Exception {
        var body=request();body.set("payment",mapper.valueToTree(Map.of("type","SEPA_DD","iban","ES9121000418450200051332","holderName","Custom Holder","holderTaxId","12345678Z","firstMonthOption","TODAY")));
        String id=submit(body).path("memberId").asText();var signed=member(id).get("paymentMethod",Document.class).get("mandateSignedAt");
        assertThat(signed).isNotNull();clock.setInstant(clock.instant().plus(Duration.ofHours(3)));
        // A fictional account number with a valid mod-97 checksum; no live account is contacted.
        result(admin(patchJson("/members/"+id,Map.of("version",member(id).get("version"),"paymentMethod",Map.of("type","SEPA_DD","sepa",Map.of("iban","ES5500000000000000000001"))))),200);
        var payment=member(id).get("paymentMethod",Document.class);
        assertThat(payment.getString("iban")).isEqualTo("ES5500000000000000000001");assertThat(payment.getString("holderName")).isEqualTo("Custom Holder");
        assertThat(payment.getString("holderTaxId")).isEqualTo("12345678Z");assertThat(payment.get("mandateSignedAt")).isEqualTo(signed);
        result(admin(patchJson("/members/"+id,Map.of("version",member(id).get("version"),"paymentMethod",Map.of("type","SEPA_DD","sepa",Map.of("holderName","Second Holder"))))),200);
        payment=member(id).get("paymentMethod",Document.class);
        assertThat(payment.getString("holderName")).isEqualTo("Second Holder");assertThat(payment.getString("iban")).isEqualTo("ES5500000000000000000001");
        assertThat(payment.getString("holderTaxId")).isEqualTo("12345678Z");assertThat(payment.get("mandateSignedAt")).isEqualTo(signed);
        // An explicit null clears the tax id; a switch to MANUAL and back is a new mandate.
        var cleared=new HashMap<String,Object>();cleared.put("holderTaxId",null);
        result(admin(patchJson("/members/"+id,Map.of("version",member(id).get("version"),"paymentMethod",Map.of("type","SEPA_DD","sepa",cleared)))),200);
        assertThat(member(id).get("paymentMethod",Document.class).get("holderTaxId")).isNull();
        result(admin(patchJson("/members/"+id,Map.of("version",member(id).get("version"),"paymentMethod",Map.of("type","MANUAL")))),200);
        result(admin(patchJson("/members/"+id,Map.of("version",member(id).get("version"),"paymentMethod",Map.of("type","SEPA_DD")))),200);
        payment=member(id).get("paymentMethod",Document.class);
        assertThat(payment.get("iban")).isNull();assertThat(payment.getString("holderName")).isEqualTo("Example "+member(id).getString("lastName1"));
        assertThat(payment.get("holderTaxId")).isNull();assertThat(payment.get("mandateSignedAt")).isNotEqualTo(signed);
    }

    // ---- Step 4 (R-04-23): the rejection consumer deactivates the rejected dogs only ----
    @Test void R_04_23_theRejectionConsumerLeavesTheDogsOfALaterReadmissionPending() throws Exception {
        var original=request();String id=submit(original).path("memberId").asText();String rejectedDog=pendingDog(id);
        reject(id,"Fictional rejection before a readmission");
        // Before the outbox runs, the applicant signs up again: a new dog (another chip).
        clock.setInstant(clock.instant().plusSeconds(60));
        ((ObjectNode)original.get("dog")).put("chip","941000004000001");assertThat(submit(original).path("memberId").asText()).isEqualTo(id);
        String newDog=pendingDog(id);assertThat(newDog).isNotEqualTo(rejectedDog);
        var reused=request();String second=submit(reused).path("memberId").asText();String secondDog=pendingDog(second);
        reject(second,"Fictional rejection before a readmission");clock.setInstant(clock.instant().plusSeconds(60));
        // … or the same chip: the readmission reuses the rejected dog (R-04-07), which is PENDING again.
        assertThat(submit(reused).path("memberId").asText()).isEqualTo(second);assertThat(pendingDog(second)).isEqualTo(secondDog);
        dispatch();
        assertThat(dogDocument(newDog).getString("status")).isEqualTo("PENDING");assertThat(dogDocument(rejectedDog).getString("status")).isEqualTo("INACTIVE");
        assertThat(dogDocument(secondDog).getString("status")).isEqualTo("PENDING");
        assertThat(member(id).getString("status")).isEqualTo("PENDING");assertThat(member(second).getString("status")).isEqualTo("PENDING");
        assertThat(events("DogDeactivated")).isEmpty();
    }

    // ---- Step 5 (§3): the Member.signup snapshot and the add-dog blocks ----
    @Test void S04_3_publicSignupSnapshotKeepsTheSubmissionAndDecisionMetadata() throws Exception {
        String id=submit(request()).path("memberId").asText();var signup=member(id).get("signup",Document.class);
        var consent=member(id).getList("consents",Document.class).getFirst();
        assertThat(signup.getString("ipHash")).isNotBlank().isEqualTo(consent.getString("ipHash"));
        assertThat(signup.getString("userAgent")).isEqualTo("Fictional agent/1.0");
        assertThat(signup.getString("paymentMethodTypeRequested")).isEqualTo("MANUAL");assertThat(signup.getString("planIdRequested")).isEqualTo(plan);
        var first=signup.get("upfront",Document.class).get("firstMonth",Document.class);
        assertThat(first.getString("option")).isEqualTo("TODAY");assertThat(first.getString("startDate")).isEqualTo("2026-01-01");
        assertThat(first.get("amountDue",Document.class).get("amountMinor",Number.class).longValue()).isEqualTo(6000);
        assertThat(dogDocument(pendingDog(id)).get("signup",Document.class).getString("submissionId")).isEqualTo(signup.getString("submissionId"));
        String dog=pendingDog(id);clock.setInstant(clock.instant().plusSeconds(120));validate(id);
        signup=member(id).get("signup",Document.class);
        assertThat(signup.get("validatedAt",Date.class).toInstant()).isEqualTo(clock.instant());assertThat(signup.getString("validatedByAccountId")).isEqualTo("minor-admin");
        assertThat(dogDocument(dog).get("signup",Document.class).getString("validatedByAccountId")).isEqualTo("minor-admin");
        assertThat(signup.containsKey("rejectedAt")).isFalse();
        String rejected=submit(request()).path("memberId").asText();String rejectedDog=pendingDog(rejected);
        reject(rejected,"Fictional incomplete application");
        var decision=member(rejected).get("signup",Document.class);
        assertThat(decision.get("rejectedAt",Date.class).toInstant()).isEqualTo(clock.instant());assertThat(decision.getString("rejectedByAccountId")).isEqualTo("minor-admin");
        assertThat(decision.getString("rejectionReason")).isEqualTo("Fictional incomplete application");
        assertThat(dogDocument(rejectedDog).get("signup",Document.class).getString("rejectionReason")).isEqualTo("Fictional incomplete application");
    }
    @Test void S04_3_addDogSubmissionsKeepOneBlockPerDogAndNeverOverwriteThePublicSignup() throws Exception {
        String id=activeMember();var original=member(id).get("signup",Document.class);
        String first=addDog(id,"941000005000001",null,"es");clock.setInstant(clock.instant().plusSeconds(60));String second=addDog(id,"941000005000002",null,"ca");
        assertThat(member(id).get("signup",Document.class)).isEqualTo(original);
        var a=dogDocument(first).get("signup",Document.class);var b=dogDocument(second).get("signup",Document.class);
        for(var block:List.of(a,b)) {
            assertThat(block.getString("source")).isEqualTo("APP_ADD_DOG");assertThat(block.getBoolean("readmission")).isFalse();
            assertThat(block.getString("ipHash")).isNotBlank();assertThat(block.getString("userAgent")).isEqualTo("Fictional app/2.0");
            assertThat(block.getString("paymentMethodTypeRequested")).isEqualTo("MANUAL");assertThat(block.getString("planIdRequested")).isEqualTo(plan);
            assertThat(block.get("upfront",Document.class).get("totalDue",Document.class).get("amountMinor",Number.class).longValue()).isPositive();
            assertThat(block.getString("submissionId")).isNotEqualTo(original.getString("submissionId"));
        }
        assertThat(a.getString("locale")).isEqualTo("es");assertThat(b.getString("locale")).isEqualTo("ca");
        assertThat(a.getString("submissionId")).isNotEqualTo(b.getString("submissionId"));
        // D2 shows the oldest pending add-dog submission (its date and source), not the public one.
        var view=review(id).path("signup");
        assertThat(view.path("source").asText()).isEqualTo("APP_ADD_DOG");assertThat(Instant.parse(view.path("submittedAt").asText())).isEqualTo(a.get("submittedAt",Date.class).toInstant());
        assertThat(review(id).path("warnings").toString()).doesNotContain("READMISSION");
        clock.setInstant(clock.instant().plusSeconds(60));validate(id,List.of(first),null);
        assertThat(dogDocument(first).get("signup",Document.class).getString("validatedByAccountId")).isEqualTo("minor-admin");
        assertThat(dogDocument(second).get("signup",Document.class).containsKey("validatedAt")).isFalse();
        reject(id,"Fictional second dog not accepted");
        var rejected=dogDocument(second).get("signup",Document.class);
        assertThat(rejected.getString("rejectionReason")).isEqualTo("Fictional second dog not accepted");assertThat(rejected.getString("rejectedByAccountId")).isEqualTo("minor-admin");
        assertThat(member(id).get("signup",Document.class)).isEqualTo(original);
    }

    // ---- Step 6 (CATALEG_ESDEVENIMENTS): event payloads ----
    @Test void S04_7_validationEventsCarryTheActorRolesMemberTriggerAndThePersistedDogs() throws Exception {
        String id=submit(request()).path("memberId").asText();String dog=pendingDog(id);
        var pending=events("DogDocumentPending");assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().get("payload",Document.class).getString("trigger")).isEqualTo("REGISTRATION");
        validate(id);
        String account=member(id).getString("accountId");
        var created=events("AccountCreated");assertThat(created).hasSize(1);
        var membership=events("MembershipChanged");assertThat(membership).hasSize(1);
        for(var event:List.of(created.getFirst(),membership.getFirst())) {
            assertThat(event.getString("actorAccountId")).as(event.getString("type")).isEqualTo("minor-admin");assertThat(event.getString("origin")).as(event.getString("type")).isEqualTo("BACKOFFICE");
        }
        var roles=membership.getFirst().get("payload",Document.class);
        assertThat(roles.getString("accountId")).isEqualTo(account);assertThat(roles.getString("clubId")).isEqualTo(club);assertThat(roles.getString("memberId")).isEqualTo(id);
        assertThat(roles.getList("before",String.class)).isEmpty();assertThat(roles.getList("after",String.class)).containsExactly("MEMBER");
        var recorded=events("UpfrontPaymentRecorded");assertThat(recorded).hasSize(2);
        assertThat(recorded).allSatisfy(e -> assertThat(e.get("payload",Document.class).getString("memberId")).isEqualTo(id));
        var validated=events("MemberValidated").getFirst().get("payload",Document.class);
        assertThat(validated.getList("dogs",Document.class)).containsExactly(new Document("dogId",dog).append("levelId",level));
        assertThat(dogDocument(dog).getString("levelId")).isEqualTo(level);assertThat(dogDocument(dog).get("levelAssignedAt")).isNotNull();
        // The first level is an assignment: no DogLevelChanged (N-09 would fire at every validation).
        assertThat(events("DogLevelChanged")).isEmpty();
    }
    @Test void S04_7_withLevelsDisabledTheEventsCarryThePersistedNullLevelNotTheRequest() throws Exception {
        parameter("levels.enabled",false);
        String id=submit(request()).path("memberId").asText();String dog=pendingDog(id);
        var body=new LinkedHashMap<String,Object>(Map.of("version",member(id).get("version"),"dogs",List.of(Map.of("dogId",dog,"levelId","ignored-level")),
                "nextInvoiceDate","2026-02-01","upfrontAmountPaid",Map.of("amountMinor",16000,"currency","EUR")));
        result(admin(postJson("/members/"+id+"/validation",body)),200);
        var dogs=events("MemberValidated").getFirst().get("payload",Document.class).getList("dogs",Document.class);
        assertThat(dogs).hasSize(1);assertThat(dogs.getFirst().getString("dogId")).isEqualTo(dog);assertThat(dogs.getFirst().get("levelId")).isNull();
        assertThat(dogDocument(dog).get("levelId")).isNull();
        String added=addDog(id,"941000006000001",null,"ca");
        result(admin(postJson("/members/"+id+"/validation",Map.of("version",member(id).get("version"),"dogs",List.of(Map.of("dogId",added,"levelId","ignored-level")),"upfrontAmountPaid",Map.of("amountMinor",0,"currency","EUR")))),200);
        var registered=events("DogRegistered").getFirst().get("payload",Document.class);
        assertThat(registered.getString("dogId")).isEqualTo(added);assertThat(registered.get("levelId")).isNull();
    }

    // ---- Step 7 (§8): N-02 and N-39 in Account.locale ----
    @Test void R_04_22_T_04_17_welcomeAndRecognitionRenderInTheAccountLocaleWithTheSignupLocaleAsFallback() throws Exception {
        var body=request();String email=body.at("/person/emails/0").asText();String id=submit(body).path("memberId").asText();dispatch();
        validate(id);
        // The account existed before (a Learn user, say) with its own language: the welcome follows it, not the form's `ca`.
        mongo.getCollection("accounts").updateOne(new Document("_id",member(id).getString("accountId")),new Document("$set",new Document("locale","en")));
        dispatch();
        var welcome=mailbox().lastTo(email);
        assertThat(welcome.locale().getLanguage()).isEqualTo("en");assertThat(welcome.subject()).isEqualTo("Welcome, Example!");
        var identity=Map.of("idDocument",body.at("/person/idDocument"),"emails",List.of(email));
        mongo.getCollection("accounts").updateOne(new Document("_id",member(id).getString("accountId")),new Document("$set",new Document("locale","es")));
        result(postJson("/signup/identity-checks",identity),200);dispatch();
        assertThat(mailbox().lastTo(email).locale().getLanguage()).isEqualTo("es");
        // An account without a language falls back to the submission's.
        mongo.getCollection("accounts").updateOne(new Document("_id",member(id).getString("accountId")),new Document("$unset",new Document("locale","")));
        clock.setInstant(clock.instant().plusSeconds(3700));result(postJson("/signup/identity-checks",identity),200);dispatch();
        assertThat(mailbox().lastTo(email).locale().getLanguage()).isEqualTo("ca");
        assertThat(collection("notifications").stream().filter(n -> "N-39".equals(n.getString("code")))).hasSize(2);
    }

    // ---- Step 11: T-04-26 on the «club mínim» seed itself (BILLING, FAMILY_GROUP and PACKS off, levels off, GENERIC) ----
    @Autowired com.agilityhub.core.platform.application.definition.ClubDefinitions definitions;
    @Autowired com.agilityhub.core.platform.application.definition.ClubDefinitionCodec codec;
    @Test void T_04_26_theMinimalClubSeedSignsUpAndValidatesWithoutMoneyFamilyOrLevels() throws Exception {
        var input=codec.read(java.nio.file.Path.of("seeds/club-minim.yaml"));input.remove("accounts");String slug="minim-"+UUID.randomUUID().toString().substring(0,8);
        ((ObjectNode)input.get("club")).put("slug",slug).put("status","ACTIVE");((ObjectNode)input.at("/domains/0")).put("host",slug+".example.test");
        club=definitions.apply(input,false).id();host=slug+".example.test";hosts.invalidate();
        var config=result(get("/api/v1/signup").header("Host",host),200);
        assertThat(config.path("steps")).hasSize(3);assertThat(config.has("paymentMethods")).isFalse();assertThat(config.has("upfront")).isFalse();assertThat(config.path("plans")).isEmpty();
        assertThat(config.has("allowFamilyGroupPending")).isFalse();
        var body=request();body.remove("planId");body.remove("payment");body.put("locale","en");
        ((ObjectNode)body.get("person")).set("idDocument",mapper.valueToTree(Map.of("type","PASSPORT","value","XA1234567")));
        ((ObjectNode)body.get("person")).set("phones",mapper.valueToTree(List.of(Map.of("prefix","+44","number","7700900123"))));
        ((ObjectNode)body.get("dog")).put("chip","ABC1234567"+sequence);
        body.set("consents",mapper.valueToTree(Map.of("privacyPolicy",Map.of("accepted",true,"version","2026-09"),"imageUse",Map.of("granted",true,"version","2026-09"))));
        var iban=body.deepCopy();iban.set("payment",mapper.valueToTree(Map.of("type","SEPA_DD","iban","DE89370400440532013000")));
        assertThat(submit(iban,400).at("/details/fieldErrors/0/field").asText()).isEqualTo("payment.iban");
        assertThat(result(postJson("/signup/family-group-lookups",Map.of("holderName","Example Holder","dogName","Example Dog")),404).path("code").asText()).isEqualTo("MODULE_DISABLED");
        var claim=body.deepCopy();claim.set("familyGroupClaim",mapper.valueToTree(Map.of("holderName","Example Holder","dogName","Example Dog","leavePending",false)));
        assertThat(submit(claim,400).at("/details/fieldErrors/0/field").asText()).isEqualTo("familyGroupClaim");
        var created=submit(body);String id=created.path("memberId").asText();
        assertThat(created.path("upfront").isMissingNode()||created.path("upfront").isNull()).isTrue();assertThat(created.at("/checkout/required").asBoolean()).isFalse();
        assertThat(member(id).get("signup",Document.class).containsKey("upfront")).isFalse();assertThat(member(id).get("signup",Document.class).containsKey("paymentMethodTypeRequested")).isFalse();
        String dog=pendingDog(id);
        result(admin(postJson("/members/"+id+"/validation",Map.of("version",member(id).get("version"),"dogs",List.of(Map.of("dogId",dog))))),200);
        assertThat(member(id).getString("status")).isEqualTo("ACTIVE");assertThat(member(id).getInteger("memberNumber")).isEqualTo(1);
        assertThat(dogDocument(dog).get("levelId")).isNull();assertThat(rows(id)).isEmpty();assertThat(collection("family_groups")).isEmpty();
    }

    // ---- Step 15 (E3-T08 round-2 review) ----
    @Test void T_04_22_eachAddDogSubmissionStaysPayableAfterAnotherOneIsValidatedWithNothingPaid() throws Exception {
        stripe();String id=activeMember();
        String a=addDog(id,"941000007000001",null,"ca");String b=addDog(id,"941000007000002",null,"ca");
        validate(id,List.of(a),0L);
        assertThat(dogDocument(a).getString("status")).isEqualTo("ACTIVE");assertThat(dogDocument(b).getString("status")).isEqualTo("PENDING");
        var due=rows(id).stream().filter(p -> "DUE".equals(p.getString("status"))).toList();
        assertThat(due.stream().map(p -> p.getString("dogId")).distinct()).containsExactlyInAnyOrder(a,b);
        String session=result(asMember(postJson("/checkout-sessions",Map.of("memberId",id,"successUrl","https://"+host+"/ok","cancelUrl","https://"+host+"/cancel")).header("Idempotency-Key",UUID.randomUUID()),id),201).path("checkoutSessionId").asText();
        var charged=fake.request(session);
        assertThat(charged.lines().stream().map(l -> l.paymentId())).containsExactlyInAnyOrderElementsOf(due.stream().map(p -> p.getString("_id")).toList());
        assertThat(charged.lines().stream().mapToLong(l -> l.amount().amountMinor()).sum()).isEqualTo(due.stream().mapToLong(p -> p.get("amountDue",Document.class).get("amountMinor",Number.class).longValue()).sum());
    }
    String checkout(String id) throws Exception {
        return result(asMember(postJson("/checkout-sessions",Map.of("memberId",id,"successUrl","https://"+host+"/ok","cancelUrl","https://"+host+"/cancel")).header("Idempotency-Key",UUID.randomUUID()),id),201).path("checkoutSessionId").asText();
    }
    List<String> charged(String session) { return fake.request(session).lines().stream().map(l -> l.paymentId()).toList(); }
    List<String> due(String memberId,String dogId) {
        return rows(memberId).stream().filter(p -> dogId.equals(p.getString("dogId"))&&"DUE".equals(p.getString("status"))).map(p -> p.getString("_id")).toList();
    }
    /** Round 2, point 1 (step 15.1, S03 R-03-14): the payable rows follow their debtor (`UpfrontPayment.memberId`), not the dog's owner. */
    @Test void R_03_14_T_04_22_theUnpaidRowsOfATransferredDogStayPayableByTheirDebtor() throws Exception {
        stripe();String debtor=activeMember(),owner=activeMember();
        String dog=addDog(debtor,"941000009000001",null,"ca");validate(debtor,List.of(dog),0L);
        var owed=due(debtor,dog);assertThat(owed).isNotEmpty();
        // The new owner has a pending add-dog of its own, so its checkout has something to charge.
        String own=addDog(owner,"941000009000002",null,"ca");var ownRows=due(owner,own);assertThat(ownRows).isNotEmpty();
        result(admin(postJson("/dogs/"+dog+"/transfer",Map.of("toMemberId",owner,"reason","Fictional transfer between members"))),200);
        assertThat(dogDocument(dog).getString("memberId")).isEqualTo(owner);assertThat(due(debtor,dog)).isEqualTo(owed);
        // The debtor still pays its rows; the new owner pays only its own.
        assertThat(charged(checkout(debtor))).containsExactlyInAnyOrderElementsOf(owed);
        assertThat(charged(checkout(owner))).containsExactlyInAnyOrderElementsOf(ownRows).doesNotContainAnyElementsOf(owed);
    }
    /** Round 2, point 2 (S04 §8): the N-03 of a rejected add-dog renders in that submission's locale, not the public signup's. */
    @Test void R_04_23_T_04_21_anAddDogRejectionRendersN03InTheLocaleOfItsOwnSubmission() throws Exception {
        String id=activeMember();assertThat(member(id).get("signup",Document.class).getString("locale")).isEqualTo("ca");
        String dog=addDog(id,"941000010000001",null,"es");assertThat(dogDocument(dog).get("signup",Document.class).getString("locale")).isEqualTo("es");
        dispatch();mailbox().clear();
        reject(id,"Fictional dog not accepted");dispatch();
        String email=member(id).getList("contactEmails",Document.class).getFirst().getString("email");
        var rejection=mailbox().lastTo(email);
        assertThat(rejection.locale().getLanguage()).isEqualTo("es");assertThat(rejection.text()).contains("Fictional dog not accepted");
        assertThat(mailbox().messages().stream().filter(m -> email.equals(m.to()))).hasSize(1);
        assertThat(collection("notifications").stream().filter(n -> "N-03".equals(n.getString("code"))&&"EMAIL".equals(n.getString("channel")))).hasSize(1);
    }
    @Test void T_04_18_nextInvoiceDateIsCheckedAgainstTheFrozenStartWhileTheQuoteIsUnchanged() throws Exception {
        // 01-01 < split day 16: ALTERNATIVE starts on 16-01 (half month), frozen at submission.
        var body=request();((ObjectNode)body.get("payment")).put("firstMonthOption","ALTERNATIVE");String id=submit(body).path("memberId").asText();
        assertThat(member(id).get("signup",Document.class).get("upfront",Document.class).get("firstMonth",Document.class).getString("startDate")).isEqualTo("2026-01-16");
        // The club moves the split day: today's parameters would start ALTERNATIVE on 20-01, the submission still starts on 16-01.
        parameter("signup.firstMonthSplitDay",20);
        var request=new LinkedHashMap<String,Object>(Map.of("version",member(id).get("version"),"dogs",List.of(Map.of("dogId",pendingDog(id),"levelId",level)),"nextInvoiceDate","2026-01-18"));
        var dry=result(admin(postJson("/members/"+id+"/validation",request).param("dryRun","true")),200);
        assertThat(dry.path("nextInvoiceDate").asText()).isEqualTo("2026-01-18");
        request.put("nextInvoiceDate","2026-01-15");
        for(boolean dryRun:List.of(true,false)) {
            var error=result(admin(postJson("/members/"+id+"/validation",request).param("dryRun",String.valueOf(dryRun))),400);
            assertThat(error.at("/details/fieldErrors/0/field").asText()).isEqualTo("nextInvoiceDate");
        }
        request.remove("nextInvoiceDate");
        assertThat(result(admin(postJson("/members/"+id+"/validation",request).param("dryRun","true")),200).path("nextInvoiceDate").asText()).isEqualTo("2026-02-01");
        assertThat(review(id).at("/proposals/nextInvoiceDate").asText()).isEqualTo("2026-02-01");
        request.put("nextInvoiceDate","2026-01-18");request.put("upfrontAmountPaid",Map.of("amountMinor",13000,"currency","EUR"));
        result(admin(postJson("/members/"+id+"/validation",request)),200);
        assertThat(result(admin(get("/api/v1/members/"+id)),200).findValuesAsText("nextInvoiceDate")).contains("2026-01-18");
    }
    @Test void T_04_21_theAdminN01OfEachQueuedAddDogDescribesItsOwnSubmission() throws Exception {
        String admin=administrator("ca");String second=UUID.randomUUID().toString();
        plan("SECOND",second,new LocalizedText(Map.of("ca","Segona modalitat","es","Segunda modalidad","en","Second offer"),"en"));
        String id=activeMember();dispatch();mailbox().clear();
        // Two add-dog submissions queued before the outbox runs: A in Spanish on the member's plan, B in Catalan on another plan.
        String a=addDog(id,"941000008000001",null,"es");String b=addDog(id,"941000008000002",second,"ca");
        String nameA=dogDocument(a).getString("name"),nameB=dogDocument(b).getString("name");
        dispatch();
        var adminMails=mailbox().messages().stream().filter(m -> (admin+"@example.test").equals(m.to())).toList();
        assertThat(adminMails).hasSize(2);
        assertThat(adminMails.stream().filter(m -> m.text().contains(nameA)).findFirst().orElseThrow().text()).contains("(Example)").doesNotContain("Segona");
        assertThat(adminMails.stream().filter(m -> m.text().contains(nameB)).findFirst().orElseThrow().text()).contains("(Segona modalitat)");
        String email=member(id).getList("contactEmails",Document.class).getFirst().getString("email");
        var applicant=mailbox().messages().stream().filter(m -> email.equals(m.to())).toList();
        assertThat(applicant.stream().filter(m -> m.text().contains(nameA)).findFirst().orElseThrow().locale().getLanguage()).isEqualTo("es");
        assertThat(applicant.stream().filter(m -> m.text().contains(nameB)).findFirst().orElseThrow().locale().getLanguage()).isEqualTo("ca");
    }

    // ---- E3-T13 (review of E3-T10 round 2): the checkout ----
    /** `signup:payment.concept.*` as the three bundles word them: what the provider must receive for each submission's locale. */
    static final Map<String,Map<String,String>> CONCEPTS=Map.of(
            "ca",Map.of("ENTRY_FEE","Entrada","FIRST_MONTH","Primer mes","ADDITIONAL_DOG_FEE","Quota del gos addicional"),
            "es",Map.of("ENTRY_FEE","Entrada","FIRST_MONTH","Primer mes","ADDITIONAL_DOG_FEE","Cuota del perro adicional"),
            "en",Map.of("ENTRY_FEE","Entry fee","FIRST_MONTH","First month","ADDITIONAL_DOG_FEE","Additional dog fee"));
    /** The description the provider received for each charged row, by row id. */
    Map<String,String> descriptions(String session) {
        var result=new HashMap<String,String>();for(var line:fake.request(session).lines()) result.put(line.paymentId(),line.description());return result;
    }
    /** The `DUE` rows of the given dogs (read before a checkout moves them to `CHECKOUT_PENDING`). */
    List<String> due(String memberId,List<String> dogIds) { var result=new ArrayList<String>();for(var dog:dogIds) result.addAll(due(memberId,dog));return result; }
    /** The code before E3-T10 overwrote `Member.signup` with each add-dog's block; the last dog's block is what such a record keeps. */
    void overwrittenByTheOldAddDog(String memberId,String dogId) {
        var block=dogDocument(dogId).get("signup",Document.class);assertThat(block.getString("source")).isEqualTo("APP_ADD_DOG");
        mongo.getCollection("members").updateOne(new Document("_id",memberId),new Document("$set",new Document("signup",block)));
    }
    /** The member leaves (S13 writes this LEFT record; no route in this stage): its dogs go INACTIVE and the rows it owed stay DUE. */
    void leave(String memberId) {
        var at=Date.from(clock.instant());
        mongo.getCollection("members").updateOne(new Document("_id",memberId),new Document("$set",new Document("status","LEFT").append("leftAt",at).append("leftReason","LEAVE_REQUEST")));
        mongo.getCollection("dogs").updateMany(new Document("memberId",memberId).append("clubId",club),new Document("$set",new Document("status","INACTIVE").append("deactivationReason","MEMBER_LEFT").append("deactivatedAt",at)));
    }
    String anonymousCheckout(String memberId,String signupToken) throws Exception {
        return result(postJson("/checkout-sessions",Map.of("memberId",memberId,"signupToken",signupToken,"successUrl","https://"+host+"/ok","cancelUrl","https://"+host+"/cancel"))
                .header("Idempotency-Key",UUID.randomUUID()).with(r->{r.setRemoteAddr("198.51.100."+(++sequence%250+1));return r;}),201).path("checkoutSessionId").asText();
    }
    /** Step 1 (review #1, R-04-26): an `APP_ADD_DOG` block left in `Member.signup` is no boundary: A's rows stay payable. */
    @Test void R_04_26_T_04_22_anAddDogBlockTheOldCodeLeftInMemberSignupIsNoCheckoutBoundary() throws Exception {
        stripe();String id=activeMember();
        String a=addDog(id,"941000011000001",null,"ca");clock.setInstant(clock.instant().plusSeconds(60));String b=addDog(id,"941000011000002",null,"ca");
        assertThat(due(id,a)).isNotEmpty();assertThat(due(id,b)).isNotEmpty();
        assertThat(dogDocument(b).get("signup",Document.class).get("submittedAt",Date.class)).isAfter(dogDocument(a).get("signup",Document.class).get("submittedAt",Date.class));
        overwrittenByTheOldAddDog(id,b);var payable=due(id,List.of(a,b));
        assertThat(charged(checkout(id))).containsExactlyInAnyOrderElementsOf(payable);
    }
    /**
     * Step 1: a real readmission (R-04-06) still leaves out the rows it superseded, also on a record where a later add-dog of
     * the old code overwrote `Member.signup`: the public signup's own block survives on its dog. Step 2 on the way: the
     * readmission's lines speak the applicant's language, whatever the LEFT record's or a later add-dog's.
     */
    @Test void R_04_06_R_04_26_T_04_22_aReadmissionStillLeavesOutTheRowsItSuperseded() throws Exception {
        stripe();var original=request();String id=submit(original).path("memberId").asText();
        validate(id,List.of(),0L);var superseded=rows(id).stream().map(p -> p.getString("_id")).toList();
        assertThat(rows(id)).isNotEmpty().allMatch(p -> "DUE".equals(p.getString("status")));
        leave(id);clock.setInstant(clock.instant().plusSeconds(60));
        original.put("locale","en");var readmitted=submit(original);assertThat(readmitted.path("memberId").asText()).isEqualTo(id);
        var readmission=rows(id).stream().map(p -> p.getString("_id")).filter(p -> !superseded.contains(p)).toList();assertThat(readmission).isNotEmpty();
        String pending=anonymousCheckout(id,readmitted.path("signupToken").asText());
        assertThat(charged(pending)).containsExactlyInAnyOrderElementsOf(readmission);
        assertThat(descriptions(pending).values()).containsExactlyInAnyOrder("Entry fee","First month");
        fake.expire(pending);validate(id,List.of(),0L);
        clock.setInstant(clock.instant().plusSeconds(60));String added=addDog(id,"941000011000003",null,"es");overwrittenByTheOldAddDog(id,added);
        var payable=new ArrayList<>(readmission);payable.addAll(due(id,added));
        String legacy=checkout(id);
        assertThat(charged(legacy)).containsExactlyInAnyOrderElementsOf(payable).doesNotContainAnyElementsOf(superseded);
        var described=descriptions(legacy);
        for(var row:rows(id)) if(described.containsKey(row.getString("_id"))) {
            String locale=added.equals(row.getString("dogId"))?"es":"en";
            assertThat(described.get(row.getString("_id"))).as("%s of %s",row.getString("signupConcept"),row.getString("dogId")).isEqualTo(CONCEPTS.get(locale).get(row.getString("signupConcept")));
        }
    }
    /** Step 2 (review #2, R-04-26): every line the provider receives is described in the locale of the submission it belongs to. */
    @Test void R_04_26_T_04_22_eachPaymentLineIsDescribedInTheLocaleOfItsOwnSubmission() throws Exception {
        stripe();String id=submit(request()).path("memberId").asText();validate(id,List.of(),0L);
        assertThat(member(id).get("signup",Document.class).getString("locale")).isEqualTo("ca");
        String spanish=addDog(id,"941000011000004",null,"es");String english=addDog(id,"941000011000005",null,"en");
        var locales=Map.of(spanish,"es",english,"en");
        var described=descriptions(checkout(id));assertThat(described).hasSize(rows(id).size());
        for(var row:rows(id)) {
            String locale=locales.getOrDefault(row.getString("dogId"),"ca");
            assertThat(described.get(row.getString("_id"))).as("%s of the %s submission",row.getString("signupConcept"),locale).isEqualTo(CONCEPTS.get(locale).get(row.getString("signupConcept")));
        }
        assertThat(described.values()).contains("Cuota del perro adicional","Additional dog fee","Entry fee","Primer mes");
    }
}

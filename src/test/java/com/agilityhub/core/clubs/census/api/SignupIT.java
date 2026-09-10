package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.test.context.SpringBootTest(properties={"shared.scheduling.enabled=false","core.security.rate-limits.enabled=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class SignupIT extends AbstractIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired MongoTemplate mongo;@Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;@Autowired HostTenantResolver hosts;
    @Autowired com.agilityhub.core.payments.application.FakeCheckoutGateway fake;
    String club,host,plan,price,level;
    int sequence;
    @BeforeEach void setup() {
        club="sgn-"+UUID.randomUUID();host=club+".example.test";plan=UUID.randomUUID().toString();price=UUID.randomUUID().toString();level=UUID.randomUUID().toString();
        ObjectNode tree=mapper.valueToTree(PlatformFixtures.club(club,host));tree.set("modules",mapper.valueToTree(Module.values()));
        tree.set("paymentProviders",mapper.valueToTree(Map.of("MANUAL",Map.of(),"SEPA_XML",Map.of())));
        clubs.save(mapper.convertValue(tree,Club.class));configs.invalidate(club);hosts.invalidate();
        var text=new LocalizedText(Map.of("ca","Example","es","Example","en","Example"),"en");
        mongo.insert(new Plan(plan,club,"MONTHLY",text,PlanType.MONTHLY,BillingMode.MONTHLY_FEE,1,new EntryFee(EntryFeeMode.STANDARD,null,null),null,null,text,null,true,true,0,true,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Price(price,club,plan,PriceConcept.MONTHLY_FEE,new Money(6000,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2020,1,1),null,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Document("_id",level).append("clubId",club).append("active",true).append("name",Map.of("en","Beginner")).append("order",0),"levels");
    }
    ObjectNode request() {
        int value=12000000+(++sequence);String national=String.format("%08d",value)+"TRWAGMYFPDXBNJZSQVHLCKE".charAt(value%23);
        return mapper.valueToTree(Map.of("locale","ca","website","","person",Map.of("idDocument",Map.of("type","DNI","value",national),"firstName","Example","lastName1","Applicant","birthDate","2000-01-01","gender","FEMALE","emails",List.of("applicant"+sequence+"@example.test"),"phones",List.of(Map.of("prefix","+34","number","600000001")),"address",Map.of("street","Example street","postalCode","99999","town","Example town")),
                "dog",Map.of("name","Example Dog","sex","FEMALE","breed","Example breed","birthMonth","2024-04","chip","941000000"+String.format("%06d",sequence)),"planId",plan,
                "payment",Map.of("type","MANUAL","firstMonthOption","TODAY"),"consents",Map.of("privacyPolicy",Map.of("accepted",true,"version","v1"),"imageUse",Map.of("granted",false,"version","v1"))));
    }
    MockHttpServletRequestBuilder postJson(String path,Object body) throws Exception {return post("/api/v1"+path).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(body));}
    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) { return request.with(jwt().jwt(j->j.subject("signup-admin").claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))); }
    JsonNode result(MockHttpServletRequestBuilder request,int status) throws Exception {
        var response=mvc.perform(request).andReturn().getResponse();var body=mapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).as("HTTP code=%s details=%s",body.path("code").asText(),body.path("details")).isEqualTo(status);return body;
    }
    JsonNode submit(Object body) throws Exception {return result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),201);}
    Document member(String id) {return mongo.getCollection("members").find(new Document("_id",id).append("clubId",club)).first();}
    List<Document> collection(String name) {return mongo.getCollection(name).find(new Document("clubId",club)).into(new ArrayList<>());}
    Map<String,Object> validation(String member,String dog,long version) {return Map.of("version",version,"dogs",List.of(Map.of("dogId",dog,"levelId",level)),"nextInvoiceDate","2026-02-01","upfrontAmountPaid",Map.of("amountMinor",16000,"currency","EUR"));}
    @com.agilityhub.core.support.AuditCovers({com.agilityhub.core.platform.application.audit.AuditAction.MEMBER_VALIDATED,com.agilityhub.core.platform.application.audit.AuditAction.DOG_LEVEL_CHANGED})
    @Test void T_04_17_publicSignupAndValidationAreAtomicOnRealCensusEntities() throws Exception {
        var config=result(get("/api/v1/signup").header("Host",host),200);assertThat(config.path("steps").size()).isEqualTo(4);
        var created=submit(request());String id=created.path("memberId").asText();String dog=collection("dogs").getFirst().getString("_id");
        assertThat(created.at("/upfront/totalDue/amountMinor").asLong()).isEqualTo(16000);
        assertThat(member(id).getString("status")).isEqualTo("PENDING");assertThat(member(id).get("consents")).isInstanceOf(List.class);
        var review=result(admin(get("/api/v1/members/"+id+"/signup").header("Host",host)),200);
        assertThat(review.path("warnings").toString()).contains("DOCUMENT_PENDING","NO_IMAGE_CONSENT","UPFRONT_UNPAID");
        var validated=result(admin(postJson("/members/"+id+"/validation",validation(id,dog,0))),200);
        assertThat(validated.path("number").asInt()).isEqualTo(1);assertThat(member(id).getString("status")).isEqualTo("ACTIVE");
        assertThat(collection("dogs").getFirst().getString("levelId")).isEqualTo(level);
        assertThat(collection("upfront_payments")).allMatch(p->p.getString("status").equals("PAID"));
        assertThat(collection("audit_entries").stream().map(d->d.getString("action"))).contains("MEMBER_VALIDATED","DOG_LEVEL_CHANGED");
    }
    @Test void T_04_23_idempotencyReplaysEncryptedResponseAndClosesSignup() throws Exception {
        var body=request();String key=UUID.randomUUID().toString();
        var first=result(postJson("/signup",body).header("Idempotency-Key",key),201);
        var second=result(postJson("/signup",body).header("Idempotency-Key",key),201);
        assertThat(second).isEqualTo(first);assertThat(collection("members")).hasSize(1);
        assertThat(collection("idempotency_records").toString()).doesNotContain(first.path("signupToken").asText());
        var duplicate=result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),422);
        assertThat(duplicate.path("code").asText()).isEqualTo("SIGNUP_ALREADY_PENDING");
    }
    @Test void T_04_13_missingAndInvalidDocumentsRollbackTheEntireSubmission() throws Exception {
        var body=request();((ObjectNode)body.get("dog")).set("documents",mapper.valueToTree(List.of(Map.of("type","VACCINATION_CARD","files",List.of(Map.of("fileKey","signup/foreign/file.pdf","name","file.pdf"))))));
        assertThat(result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),400).path("code").asText()).isEqualTo("FILE_NOT_FOUND");
        assertThat(collection("members")).isEmpty();assertThat(collection("dogs")).isEmpty();
        assertThat(result(postJson("/signup/upload-urls",Map.of("fileName","video.mp4","contentType","video/mp4","sizeBytes",10)),400).path("code").asText()).isEqualTo("FILE_TYPE_NOT_ALLOWED");
    }
    @Autowired com.agilityhub.core.clubs.census.application.SignupService signupService;
    @Autowired com.agilityhub.core.shared.application.OutboxDispatcher outbox;
    void parameter(String key,Object value) {
        mongo.remove(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("clubId").is(club).and("key").is(key)),Parameter.class);
        mongo.insert(new Parameter(UUID.randomUUID().toString(),club,key,value,"unknown","club",null,List.of(),0L,clock.instant()));configs.invalidate(club);signupService.invalidateConfiguration(club);
    }
    void providers(boolean stripe) {
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("paymentProviders",new Document("MANUAL",new Document("enabled",true)).append("SEPA_XML",new Document("enabled",true)).append("STRIPE",new Document("enabled",stripe)))));
        configs.invalidate(club);signupService.invalidateConfiguration(club);
    }
    String dog(String id) {return collection("dogs").stream().filter(d->id.equals(d.getString("memberId"))&&"PENDING".equals(d.getString("status"))).findFirst().orElseThrow().getString("_id");}
    JsonNode validate(String id,long amount) throws Exception {
        var body=new LinkedHashMap<>(validation(id,dog(id),((Number)member(id).get("version")).longValue()));body.put("upfrontAmountPaid",Map.of("amountMinor",amount,"currency","EUR"));
        return result(admin(postJson("/members/"+id+"/validation",body)),200);
    }
    MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder request,String id) {
        String account=member(id).getString("accountId");return request.with(jwt().jwt(j->j.subject(account).claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_MEMBER")));
    }
    JsonNode reject(String id) throws Exception {return result(admin(postJson("/members/"+id+"/rejection",Map.of("version",member(id).get("version"),"reason","Fictional rejection reason"))),200);}
    @Test void T_04_11_identityRecognitionUsesPrimaryEmailAndDocumentPrecedence() throws Exception {
        var original=request();var created=submit(original);String id=created.path("memberId").asText();validate(id,16000);
        var identity=Map.of("idDocument",original.at("/person/idDocument"),"emails",List.of("another@example.test"));
        assertThat(result(postJson("/signup/identity-checks",identity),200).path("result").asText()).isEqualTo("VERIFICATION_SENT");
        var newer=request();var emailMatch=Map.of("idDocument",newer.at("/person/idDocument"),"emails",List.of(original.at("/person/emails/0").asText()));
        assertThat(result(postJson("/signup/identity-checks",emailMatch),200).path("maskedEmail").asText()).doesNotContain("applicant","example.test");
        mongo.getCollection("members").updateOne(new Document("_id",id),new Document("$push",new Document("contactEmails",new Document("email","secondary@example.test"))));
        assertThat(result(postJson("/signup/identity-checks",Map.of("idDocument",newer.at("/person/idDocument"),"emails",List.of("secondary@example.test"))),200).path("result").asText()).isEqualTo("NEW");
        assertThat(result(postJson("/signup",original).header("Idempotency-Key",UUID.randomUUID()),409).path("code").asText()).isEqualTo("MEMBER_ALREADY_EXISTS");
        var pending=submit(newer);long events=collection("domain_events").size();
        assertThat(result(postJson("/signup/identity-checks",Map.of("idDocument",newer.at("/person/idDocument"),"emails",List.of("unknown@example.test"))),200).path("result").asText()).isEqualTo("SIGNUP_ALREADY_PENDING");
        assertThat(collection("domain_events")).hasSize((int)events);
        mongo.getCollection("members").updateOne(new Document("_id",id),new Document("$unset",new Document("accountId","")));
        assertThat(result(postJson("/signup/identity-checks",identity),200).path("result").asText()).isEqualTo("CONTACT_CLUB");
    }
    @Test void T_04_12_readmissionReusesTheMemberNumberAndOwnInactiveDog() throws Exception {
        var original=request();String id=submit(original).path("memberId").asText();String dog=dog(id);reject(id);
        mongo.getCollection("members").updateOne(new Document("_id",id),new Document("$set",new Document("memberNumber",214)));
        assertThat(submit(original).path("memberId").asText()).isEqualTo(id);assertThat(dog(id)).isEqualTo(dog);
        assertThat(collection("members")).hasSize(1);assertThat(collection("dogs")).hasSize(1);
        assertThat(member(id).getInteger("memberNumber")).isEqualTo(214);assertThat(member(id).get("signup",Document.class).getBoolean("readmission")).isTrue();
        var foreign=request();((ObjectNode)foreign.get("dog")).put("chip",original.at("/dog/chip").asText());
        assertThat(result(postJson("/signup",foreign).header("Idempotency-Key",UUID.randomUUID()),422).path("code").asText()).isEqualTo("DOG_CHIP_ALREADY_REGISTERED");
    }
    @Test void T_04_13_signedUploadClaimsOnlyExistingTenantFilesAndEnforcesLimits() throws Exception {
        byte[] bytes="fictional vaccination document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var upload=result(postJson("/signup/upload-urls",Map.of("fileName","vaccination.pdf","contentType","application/pdf","sizeBytes",bytes.length)),200);
        assertThat(upload.path("fileKey").asText()).startsWith("signup/"+club+"/202601/");
        mvc.perform(put(java.net.URI.create(upload.path("uploadUrl").asText())).header("Host",host).contentType("application/pdf").content(bytes)).andExpect(status().isNoContent());
        var body=request();((ObjectNode)body.get("dog")).set("documents",mapper.valueToTree(List.of(Map.of("type","VACCINATION_CARD","files",List.of(Map.of("fileKey",upload.path("fileKey").asText(),"name","vaccination.pdf"))))));
        String id=submit(body).path("memberId").asText();assertThat(collection("dog_documents").getFirst().getString("state")).isEqualTo("RECEIVED");
        var review=result(admin(get("/api/v1/members/"+id+"/signup").header("Host",host)),200);
        var download=java.net.URI.create(review.at("/dogs/0/documents/0/files/0/downloadUrl").asText());
        mvc.perform(admin(get(download).header("Host",host))).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        mvc.perform(get(download).header("Host",host)).andExpect(status().isUnauthorized());
        validate(id,16000);
        mvc.perform(asMember(get(download).header("Host",host),id)).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        String other="files-"+UUID.randomUUID().toString().substring(0,8),otherHost=other+".example.test";
        clubs.save(PlatformFixtures.club(other,otherHost));hosts.invalidate();
        mvc.perform(get(download).header("Host",otherHost).with(jwt().jwt(j->j.subject("foreign-admin").claim("clubId",other)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andExpect(status().isNotFound());
        clock.setInstant(clock.instant().plusSeconds(301));
        mvc.perform(admin(get(download).header("Host",host))).andExpect(status().isForbidden());
        assertThat(result(postJson("/signup/upload-urls",Map.of("fileName","large.pdf","contentType","application/pdf","sizeBytes",30*1024*1024)),400).path("code").asText()).isEqualTo("FILE_TOO_LARGE");
        parameter("signup.requireDogDocumentAtSignup",true);
        assertThat(result(postJson("/signup",request()).header("Idempotency-Key",UUID.randomUUID()),422).path("code").asText()).isEqualTo("DOG_DOCUMENT_REQUIRED");
    }
    @Test void T_04_14_optionalIbanProducesWarningsAndInvalidMethodsFail() throws Exception {
        var body=request();body.set("payment",mapper.valueToTree(Map.of("type","SEPA_DD","firstMonthOption","TODAY")));String id=submit(body).path("memberId").asText();
        assertThat(result(admin(get("/api/v1/members/"+id+"/signup").header("Host",host)),200).path("warnings").toString()).contains("ACCOUNT_NOT_PROVIDED");
        var list=result(admin(get("/api/v1/members").header("Host",host).param("filter","signupPending:eq:true")),200);
        assertThat(list.path("items").toString()).contains("ACCOUNT_NOT_PROVIDED");
        body=request();body.set("payment",mapper.valueToTree(Map.of("type","SEPA_DD","iban","ES0000000000000000000000")));
        assertThat(result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),400).path("code").asText()).isEqualTo("INVALID_IBAN");
        body=request();body.set("payment",mapper.valueToTree(Map.of("type","CARD")));
        assertThat(result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),422).path("code").asText()).isEqualTo("PAYMENT_METHOD_NOT_AVAILABLE");
    }
    @Test void T_04_15_versionedConsentIsMandatoryAndImmutable() throws Exception {
        var body=request();((ObjectNode)body.at("/consents/privacyPolicy")).put("accepted",false);
        assertThat(result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),400).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        ((ObjectNode)body.at("/consents/privacyPolicy")).put("accepted",true).put("version","old");
        assertThat(result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),422).path("code").asText()).isEqualTo("CONSENT_VERSION_OUTDATED");
        ((ObjectNode)body.at("/consents/privacyPolicy")).put("version","v1");String id=submit(body).path("memberId").asText();
        var ledger=member(id).getList("consents",Document.class);assertThat(ledger).hasSize(2);assertThat(ledger).allSatisfy(entry->{assertThat(entry.get("acceptedAt")).isNotNull();assertThat(entry.getString("locale")).isEqualTo("ca");assertThat(entry.getString("ipHash")).isNotBlank();});
        result(admin(patch("/api/v1/members/"+id).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",0,"consents",Map.of("imageRights",Map.of("granted",true)))))),400);
        assertThat(member(id).get("consents")).isEqualTo(ledger);
    }
    @Test void T_04_16_familyClaimResolvesAgainAndValidationCreatesTheGroup() throws Exception {
        var holder=request();((ObjectNode)holder.get("person")).put("firstName","Holder").put("lastName1","Example");String holderId=submit(holder).path("memberId").asText();
        assertThat(result(postJson("/signup/family-group-lookups",Map.of("holderName","Holder Example","dogName","Example Dog")),200).path("result").asText()).isEqualTo("FOUND");
        var body=request();body.set("familyGroupClaim",mapper.valueToTree(Map.of("holderName","Holder Example","dogName","Example Dog","leavePending",false)));String id=submit(body).path("memberId").asText();
        assertThat(member(id).get("familyGroupClaim",Document.class).getString("holderMemberId")).isEqualTo(holderId);validate(id,16000);
        assertThat(collection("family_groups").getFirst().getList("memberIds",String.class)).containsExactlyInAnyOrder(holderId,id);
        body=request();body.set("familyGroupClaim",mapper.valueToTree(Map.of("holderName","Missing Holder","dogName","Unknown","leavePending",true)));String pending=submit(body).path("memberId").asText();
        assertThat(member(pending).get("familyGroupClaim",Document.class).getString("status")).isEqualTo("NOT_FOUND_PENDING");
    }
    @Test void T_04_18_dryRunNeverWritesAndValidationRequiresLevelAndInvoiceDate() throws Exception {
        String id=submit(request()).path("memberId").asText();String dog=dog(id);
        var body=new LinkedHashMap<String,Object>(Map.of("version",0,"dogs",List.of(Map.of("dogId",dog)),"upfrontAmountPaid",Map.of("amountMinor",0,"currency","EUR")));
        assertThat(result(admin(postJson("/members/"+id+"/validation",body)),422).path("code").asText()).isEqualTo("LEVEL_REQUIRED");
        body.put("dogs",List.of(Map.of("dogId",dog,"levelId",level)));
        assertThat(result(admin(postJson("/members/"+id+"/validation",body)),422).path("code").asText()).isEqualTo("NEXT_INVOICE_DATE_REQUIRED");
        var before=member(id);var payments=collection("upfront_payments");var events=collection("domain_events");
        var preview=result(admin(postJson("/members/"+id+"/validation",body).param("dryRun","true")),200);
        assertThat(preview.at("/upfront/totalDue/amountMinor").asLong()).isEqualTo(16000);assertThat(member(id)).isEqualTo(before);assertThat(collection("upfront_payments")).isEqualTo(payments);assertThat(collection("domain_events")).isEqualTo(events);
    }
    @com.agilityhub.core.support.AuditCovers(com.agilityhub.core.platform.application.audit.AuditAction.SIGNUP_REJECTED)
    @Test void T_04_19_rejectionCancelsDuePaymentsAndCannotRepeat() throws Exception {
        String id=submit(request()).path("memberId").asText();reject(id);
        assertThat(member(id).getString("leftReason")).isEqualTo("SIGNUP_REJECTED");assertThat(collection("dogs").getFirst().getString("status")).isEqualTo("INACTIVE");
        assertThat(collection("upfront_payments")).allMatch(p->"CANCELLED".equals(p.getString("status")));
        assertThat(result(admin(postJson("/members/"+id+"/rejection",Map.of("version",1,"reason","Again"))),409).path("code").asText()).isEqualTo("INVALID_STATE");
    }
    @com.agilityhub.core.support.AuditCovers(com.agilityhub.core.platform.application.audit.AuditAction.SIGNUP_EDITED)
    @Test void T_04_20_pendingEditsEmitSignupEditedAndRejectStaleVersion() throws Exception {
        String id=submit(request()).path("memberId").asText();
        var patch=patch("/api/v1/members/"+id).header("Host",host).contentType("application/json").content("{\"version\":0,\"firstName\":\"Updated\"}");result(admin(patch),200);
        assertThat(collection("domain_events").stream().map(e->e.getString("type"))).contains("SignupEdited").doesNotContain("MemberUpdated");
        result(admin(patch("/api/v1/members/"+id).header("Host",host).contentType("application/json").content("{\"version\":0,\"firstName\":\"Stale\"}")),409);
    }
    @Test void T_04_21_addDogKeepsMemberIdentityAndUsesDogRegistered() throws Exception {
        String id=submit(request()).path("memberId").asText();validate(id,16000);String account=member(id).getString("accountId");int number=member(id).getInteger("memberNumber");
        var body=Map.of("dog",request().get("dog"),"documents",List.of(),"additionalDogOption","ALTERNATIVE");
        var added=result(asMember(postJson("/me/dogs/signup",body).header("Idempotency-Key",UUID.randomUUID()),id),201);
        assertThat(added.at("/checkout/memberId").asText()).isEqualTo(id);assertThat(added.at("/upfront/totalDue/amountMinor").asLong()).isEqualTo(10000);
        validate(id,10000);assertThat(member(id).getString("accountId")).isEqualTo(account);assertThat(member(id).getInteger("memberNumber")).isEqualTo(number);
        assertThat(collection("domain_events").stream().filter(e->"MemberValidated".equals(e.getString("type")))).hasSize(1);
        assertThat(collection("domain_events").stream().filter(e->"DogRegistered".equals(e.getString("type")))).hasSize(1);
    }
    @Test void T_04_22_checkoutCapabilitiesCompletionExpiryAndRefundWarning() throws Exception {
        providers(true);var body=request();body.set("payment",mapper.valueToTree(Map.of("type","CARD","firstMonthOption","TODAY")));var submitted=submit(body);String id=submitted.path("memberId").asText();
        assertThat(submitted.at("/checkout/required").asBoolean()).isTrue();
        var checkout=Map.of("memberId",id,"signupToken",submitted.path("signupToken").asText(),"successUrl","https://"+host+"/success","cancelUrl","https://"+host+"/cancel");
        var session=result(postJson("/checkout-sessions",checkout).header("Idempotency-Key",UUID.randomUUID()),201);String sid=session.path("checkoutSessionId").asText();
        assertThat(fake.request(sid).lines()).hasSize(2);assertThat(fake.request(sid).metadata().get("memberId")).isEqualTo(id);
        assertThat(collection("upfront_payments")).allMatch(p->"CHECKOUT_PENDING".equals(p.getString("status")));fake.expire(sid);
        assertThat(collection("upfront_payments")).allMatch(p->"DUE".equals(p.getString("status")));
        sid=result(postJson("/checkout-sessions",checkout).header("Idempotency-Key",UUID.randomUUID()),201).path("checkoutSessionId").asText();fake.complete(sid);fake.complete(sid);
        assertThat(member(id).get("paymentMethod",Document.class).get("card",Document.class).getString("last4")).isEqualTo("4242");
        assertThat(reject(id).path("paidPaymentRequiresRefund").asBoolean()).isTrue();assertThat(collection("upfront_payments")).allMatch(p->"PAID".equals(p.getString("status")));
        var wrong=new LinkedHashMap<>(checkout);wrong.put("memberId",UUID.randomUUID().toString());assertThat(result(postJson("/checkout-sessions",wrong).header("Idempotency-Key",UUID.randomUUID()),401).path("code").asText()).isEqualTo("UNAUTHENTICATED");
    }
    @Test void T_04_24_pendingAgeDoesNotExpireOrDeleteTheSignup() throws Exception {
        String id=submit(request()).path("memberId").asText();clock.setInstant(clock.instant().plus(Duration.ofDays(45)));
        var review=result(admin(get("/api/v1/members/"+id+"/signup").header("Host",host)),200);assertThat(review.at("/signup/pendingDays").asInt()).isEqualTo(45);assertThat(member(id).getString("status")).isEqualTo("PENDING");
    }
    @Test void T_04_25_crossTenantAndRoleBoundariesRemainClosed() throws Exception {
        String id=submit(request()).path("memberId").asText();String other="other-"+UUID.randomUUID().toString().substring(0,8);String otherHost=other+".example.test";clubs.save(PlatformFixtures.club(other,otherHost));hosts.invalidate();
        var foreign=get("/api/v1/members/"+id+"/signup").header("Host",otherHost).with(jwt().jwt(j->j.subject("foreign-admin").claim("clubId",other)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
        result(foreign,404);
        for(String role:List.of("MEMBER","INSTRUCTOR")) result(get("/api/v1/members/"+id+"/signup").header("Host",host).with(jwt().jwt(j->j.subject("other").claim("clubId",club)).authorities(new SimpleGrantedAuthority("ROLE_"+role))),403);
        result(postJson("/me/dogs/signup",Map.of("dog",request().get("dog"),"documents",List.of())).header("Idempotency-Key",UUID.randomUUID()),401);
    }
    @Test void T_04_26_modulesOffAllowSignupWithoutMoneyFamilyOrLevels() throws Exception {
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("modules",List.of())));configs.invalidate(club);signupService.invalidateConfiguration(club);parameter("levels.enabled",false);
        var config=result(get("/api/v1/signup").header("Host",host),200);assertThat(config.path("steps")).hasSize(3);assertThat(config.has("paymentMethods")).isFalse();
        var body=request();body.remove("payment");String id=submit(body).path("memberId").asText();
        result(admin(postJson("/members/"+id+"/validation",Map.of("version",0,"dogs",List.of(Map.of("dogId",dog(id)))))),200);assertThat(collection("upfront_payments")).isEmpty();assertThat(collection("dogs").getFirst().get("levelId")).isNull();
    }
    @Test void T_04_28_honeypotAndBodySizeHaveNoPersistenceEffects() throws Exception {
        mvc.perform(postJson("/signup",Map.of("website","spam")).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isAccepted());assertThat(collection("members")).isEmpty();
        var body=request();body.put("website","x".repeat(65*1024));result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),400);assertThat(collection("members")).isEmpty();
    }

    @Test void T_04_27_twentyConcurrentValidationsAllocateConsecutiveNumbers() throws Exception {
        var ids=new ArrayList<String>();
        for(int i=1;i<=20;i++) { final String ip="203.0.113."+i;ids.add(result(postJson("/signup",request()).header("Idempotency-Key",UUID.randomUUID()).with(r->{r.setRemoteAddr(ip);return r;}),201).path("memberId").asText()); }
        var bodies=ids.stream().map(id->validation(id,dog(id),0)).toList();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(20)) {
            var latch=new java.util.concurrent.CountDownLatch(1);var futures=new ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<ids.size();i++) { final String id=ids.get(i);final var body=bodies.get(i);futures.add(executor.submit(()->{latch.await();return result(admin(postJson("/members/"+id+"/validation",body)),200).path("number").asInt();})); }
            latch.countDown();var numbers=new ArrayList<Integer>();for(var future:futures) numbers.add(future.get(40,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(numbers).containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.rangeClosed(1,20).boxed().toList());
        }
        result(admin(postJson("/members/"+ids.getFirst()+"/validation",bodies.getFirst())),409);
    }
    @Test void T_04_28_sixthHourlySubmissionIsRateLimitedWithRetryAfter() throws Exception {
        for(int i=0;i<5;i++) mvc.perform(postJson("/signup",Map.of("website","bot")).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isAccepted());
        mvc.perform(postJson("/signup",Map.of("website","bot")).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        assertThat(collection("members")).isEmpty();
    }

    @Autowired com.agilityhub.core.shared.application.OutboxDispatcher dispatcher;
    @Autowired com.agilityhub.core.clubs.messaging.application.EmailSender sender;
    @Autowired com.agilityhub.core.identity.application.ImpersonationService impersonations;
    private String administrator(String language) {
        String id=UUID.randomUUID().toString();
        mongo.insert(new com.agilityhub.core.identity.persistence.Account(id,id+"@example.test","Example Admin",language,null,Set.of(),com.agilityhub.core.identity.persistence.Account.Status.ACTIVE,null,Map.of(),false,clock.instant()));
        mongo.insert(new com.agilityhub.core.identity.persistence.Membership(id,id,club,null,Set.of(com.agilityhub.core.identity.domain.Role.ADMIN),com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE,com.agilityhub.core.identity.domain.Role.ADMIN));
        return id;
    }
    private void dispatch() {
        for(int i=0;i<12;i++) dispatcher.dispatch();
        assertThat(collection("domain_events").stream().filter(e->!"PUBLISHED".equals(e.getString("status"))).map(e->e.getString("type")+":"+e.getString("lastError"))).isEmpty();
    }
    @Test void T_04_17_T_04_11_notificationsUseOutboxApplicantAndAdminLocalesWithoutPersistingLinks() throws Exception {
        var mailbox=(com.agilityhub.core.clubs.messaging.application.FakeEmailSender)sender;mailbox.clear();String admin=administrator("es");
        String id=submit(request()).path("memberId").asText();dispatch();
        assertThat(mailbox.lastTo("applicant1@example.test").locale().getLanguage()).isEqualTo("ca");
        assertThat(mailbox.lastTo(admin+"@example.test").locale().getLanguage()).isEqualTo("es");
        assertThat(collection("notifications").stream().filter(n->"N-01".equals(n.getString("code")))).hasSize(3);
        validate(id,16000);mongo.getCollection("accounts").updateOne(new Document("_id",member(id).getString("accountId")),new Document("$set",new Document("locale","en")));dispatch();
        var welcome=mailbox.lastTo("applicant1@example.test");
        assertThat(welcome.subject()).isEqualTo("Benvinguda, Example!");
        assertThat(welcome.text().contains("http")).isTrue();assertThat(welcome.text().contains("[[")).isFalse();
        var token=collection("magic_link_tokens").stream().filter(t->"WELCOME".equals(t.getString("purpose"))).findFirst().orElseThrow();
        assertThat(Duration.between(token.getDate("createdAt").toInstant(),token.getDate("expiresAt").toInstant())).isEqualTo(Duration.ofDays(7));
        int count=mailbox.messages().size();dispatch();assertThat(mailbox.messages()).hasSize(count);
        result(postJson("/signup/identity-checks",Map.of("idDocument",Map.of("type","DNI","value","12000001"+"TRWAGMYFPDXBNJZSQVHLCKE".charAt(12000001%23)),"emails",List.of("applicant1@example.test"))),200);dispatch();
        assertThat(collection("magic_link_tokens").stream().filter(t->"RECOGNITION".equals(t.getString("purpose"))).map(t->t.getString("redirectUri"))).containsExactly("/gossos/nou");
        assertThat(mailbox.lastTo("applicant1@example.test").text().contains("redirect=%2Fgossos%2Fnou")).isTrue();
        assertThat(collection("notifications").stream().filter(n->"N-02".equals(n.getString("code")))).hasSize(2);
        assertThat(collection("notifications").stream().filter(n->"N-39".equals(n.getString("code")))).hasSize(1);
        var add=result(asMember(postJson("/me/dogs/signup",Map.of("dog",request().get("dog"),"documents",List.of())).header("Idempotency-Key",UUID.randomUUID()),id),201);dispatch();
        validate(id,13000);dispatch();
        assertThat(collection("notifications").stream().filter(n->"N-37".equals(n.getString("code")))).hasSize(1);
        assertThat(collection("notifications").stream().filter(n->"N-02".equals(n.getString("code")))).hasSize(2);
        result(asMember(postJson("/me/dogs/signup",Map.of("dog",request().get("dog"),"documents",List.of())).header("Idempotency-Key",UUID.randomUUID()),id),201);reject(id);dispatch();
        assertThat(collection("notifications").stream().filter(n->"N-03".equals(n.getString("code")))).hasSize(2);
        assertThat(collection("notifications").toString().contains("token=")).isFalse();
    }
    @Test void T_04_21_impersonatedAddDogRecordsBothActorsAndKeepsMemberActiveOnRejection() throws Exception {
        String id=submit(request()).path("memberId").asText();validate(id,16000);String admin=administrator("en");
        com.agilityhub.core.identity.application.ImpersonationService.Issued issued;
        try(var tenant=com.agilityhub.core.shared.application.TenantContext.open(club)) { issued=impersonations.create(admin,id,"Example support request"); }
        var call=postJson("/me/dogs/signup",Map.of("dog",request().get("dog"),"documents",List.of())).header("Idempotency-Key",UUID.randomUUID())
                .with(jwt().jwt(issued.token()).authorities(new SimpleGrantedAuthority("ROLE_MEMBER")));
        result(call,201);
        assertThat(collection("audit_entries").stream().filter(e->"SIGNUP_EDITED".equals(e.getString("action")))).anySatisfy(e->{assertThat(e.getString("actorAccountId")).isEqualTo(admin);assertThat(e.getString("impersonatedMemberId")).isEqualTo(id);assertThat(e.getString("origin")).isEqualTo("BACKOFFICE");});
        reject(id);assertThat(member(id).getString("status")).isEqualTo("ACTIVE");assertThat(collection("dogs").stream().filter(d->"ACTIVE".equals(d.getString("status")))).hasSize(1);
    }
    @Test void T_04_20_pendingPaymentAndDocumentEditsPreserveTheConsentLedger() throws Exception {
        var body=request();body.set("payment",mapper.valueToTree(Map.of("type","SEPA_DD")));String id=submit(body).path("memberId").asText();var original=member(id).get("consents");
        // A fictional account number with a valid mod-97 checksum; no live account is contacted.
        String iban="ES5500000000000000000001";
        result(admin(patch("/api/v1/members/"+id).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",0,"paymentMethod",Map.of("type","SEPA_DD","sepa",Map.of("iban",iban)))))),200);
        var review=result(admin(get("/api/v1/members/"+id+"/signup").header("Host",host)),200);
        assertThat(review.path("warnings").toString()).doesNotContain("ACCOUNT_NOT_PROVIDED");assertThat(review.toString().contains(iban)).isFalse();assertThat(member(id).get("consents")).isEqualTo(original);
        result(admin(patch("/api/v1/dogs/"+dog(id)).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",0,"birthMonth","2023-05","notesToInstructors","Updated note","documents",List.of())))),200);
        assertThat(collection("dogs").getFirst().get("instructorNote",Document.class).getString("text")).isEqualTo("Updated note");
        validate(id,16000);
        long version=member(id).getLong("version");
        result(admin(patch("/api/v1/members/"+id).header("Host",host).contentType("application/json").content(mapper.writeValueAsBytes(Map.of("version",version,"consents",Map.of("imageRights",Map.of("granted",true)))))),200);
        var updated=member(id).getList("consents",Document.class);assertThat(updated.subList(0,2)).isEqualTo(original);assertThat(updated).hasSize(3);assertThat(updated.getLast().getBoolean("granted")).isTrue();
    }
    @Test void T_04_27_validationAndRejectionRaceHasExactlyOneWinner() throws Exception {
        String id=submit(request()).path("memberId").asText();var accept=validation(id,dog(id),0);var reject=Map.of("version",0,"reason","Example rejection");
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=executor.submit(()->{start.await();return mvc.perform(admin(postJson("/members/"+id+"/validation",accept))).andReturn().getResponse().getStatus();});
            var b=executor.submit(()->{start.await();return mvc.perform(admin(postJson("/members/"+id+"/rejection",reject))).andReturn().getResponse().getStatus();});
            start.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(collection("domain_events").stream().filter(e->Set.of("MemberValidated","SignupRejected").contains(e.getString("type")))).hasSize(1);
    }
    @Test void T_04_23_hostScopedKeysRejectChangedBodiesAndCapabilitiesExpire() throws Exception {
        var body=request();String key=UUID.randomUUID().toString();var created=result(postJson("/signup",body).header("Idempotency-Key",key),201);
        assertThat(collection("idempotency_records")).hasSize(1);
        ((ObjectNode)body.get("person")).put("firstName","Changed");assertThat(result(postJson("/signup",body).header("Idempotency-Key",key),409).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        String id=created.path("memberId").asText();clock.setInstant(clock.instant().plus(Duration.ofHours(24)));
        result(postJson("/checkout-sessions",Map.of("memberId",id,"signupToken",created.path("signupToken").asText(),"successUrl","https://"+host+"/ok","cancelUrl","https://"+host+"/cancel")).header("Idempotency-Key",UUID.randomUUID()),401);
        parameter("signup.enabled",false);signupService.invalidateConfiguration(club);
        var config=result(get("/api/v1/signup").header("Host",host),200);assertThat(config.properties()).hasSize(2);assertThat(config.path("enabled").asBoolean()).isFalse();
        assertThat(result(postJson("/signup",request()).header("Idempotency-Key",UUID.randomUUID()),422).path("code").asText()).isEqualTo("SIGNUP_CLOSED");
    }
    @Test void T_04_28_dailyLimitAndElevenFilesRejectWithoutCensusWrites() throws Exception {
        var body=request();var files=java.util.stream.IntStream.range(0,11).mapToObj(i->Map.of("fileKey","signup/"+club+"/missing/"+i,"name","file.pdf")).toList();((ObjectNode)body.get("dog")).set("documents",mapper.valueToTree(List.of(Map.of("type","VACCINATION_CARD","files",files))));
        result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),400);
        for(int hour=0;hour<4;hour++) {clock.setInstant(clock.instant().plusSeconds(3601));for(int i=0;i<(hour==3?4:5);i++) mvc.perform(postJson("/signup",Map.of("website","bot")).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isAccepted());}
        clock.setInstant(clock.instant().plusSeconds(3601));mvc.perform(postJson("/signup",Map.of("website","bot")).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isTooManyRequests());assertThat(collection("members")).isEmpty();
    }
    private String offer(PlanType type, int dogs, long amount) {
        String id=UUID.randomUUID().toString();String code="EXAMPLE_"+sequence++;
        var name=new LocalizedText(Map.of("ca","Example offer","es","Oferta de ejemplo","en","Example offer"),"en");
        mongo.insert(new Plan(id,club,code,name,type,type==PlanType.MONTHLY?BillingMode.MONTHLY_FEE:null,dogs,new EntryFee(type==PlanType.MONTHLY?EntryFeeMode.STANDARD:EntryFeeMode.NONE,null,null),
                type==PlanType.PACK?new Pack(6,3):null,type==PlanType.SINGLE_CLASS?new SingleClass(ChargeMode.PAY_TO_BOOK,CancelPolicy.REFUND):null,name,new Texts(name,name,name),true,true,1,true,0,clock.instant(),clock.instant(),null,null));
        mongo.insert(new Price(UUID.randomUUID().toString(),club,id,type==PlanType.MONTHLY?PriceConcept.MONTHLY_FEE:type==PlanType.PACK?PriceConcept.PACK:PriceConcept.SINGLE_CLASS,new Money(amount,"EUR"),java.math.BigDecimal.ZERO,LocalDate.of(2020,1,1),null,0,clock.instant(),clock.instant(),null,null));
        signupService.invalidateConfiguration(club);return id;
    }
    @Test void T_04_18_planChangesRecalculateDueAndPreservePartialPayments() throws Exception {
        String id=submit(request()).path("memberId").asText();String dog=dog(id);String pack=offer(PlanType.PACK,1,13500);
        var request=new LinkedHashMap<String,Object>(Map.of("version",0,"dogs",List.of(Map.of("dogId",dog,"levelId",level)),"planId",pack,"upfrontAmountPaid",Map.of("amountMinor",13500,"currency","EUR")));
        var preview=result(admin(postJson("/members/"+id+"/validation",request).param("dryRun","true")),200);assertThat(preview.at("/upfront/totalDue/amountMinor").asLong()).isEqualTo(13500);
        result(admin(postJson("/members/"+id+"/validation",request)),200);
        assertThat(collection("upfront_payments").stream().filter(p->"PAID".equals(p.getString("status"))).map(p->p.getString("concept"))).containsExactly("PACK");
        String second=submit(request()).path("memberId").asText();String secondDog=dog(second);
        try(var tenant=com.agilityhub.core.shared.application.TenantContext.open(club)) {
            transactions.run(()->{payments.allocate(second,List.of(secondDog),new Money(5000,"EUR"));payments.replace(second,List.of(secondDog),List.of(new com.agilityhub.core.payments.application.UpfrontPayments.Charge("PACK",secondDog,new Money(13500,"EUR"))));return null;});
            assertThat(payments.due(second,List.of(secondDog),"EUR").amountMinor()).isEqualTo(8500);
            assertThat(payments.paid(second,List.of(secondDog),"EUR").amountMinor()).isEqualTo(5000);
        }
    }
    @Autowired com.agilityhub.core.payments.application.UpfrontPayments payments;
    @Autowired com.agilityhub.core.identity.application.IdentityTransactions transactions;
    @Autowired com.agilityhub.core.clubs.signup.application.SignupPolicy policy;
    @Test void T_04_16_partialManualAllocationPaysEntryBeforeMonthlyFeeAndRejectsExcess() throws Exception {
        String id=submit(request()).path("memberId").asText();String dog=dog(id);
        var request=new LinkedHashMap<>(validation(id,dog,0));request.put("upfrontAmountPaid",Map.of("amountMinor",16001,"currency","EUR"));
        assertThat(result(admin(postJson("/members/"+id+"/validation",request)),422).path("code").asText()).isEqualTo("UPFRONT_AMOUNT_EXCEEDS_DUE");
        validate(id,5000);
        assertThat(collection("upfront_payments").stream().filter(p->"ENTRY_FEE".equals(p.getString("concept"))).findFirst().orElseThrow().getString("status")).isEqualTo("PARTIAL");
        assertThat(collection("upfront_payments").stream().filter(p->"FIRST_MONTH".equals(p.getString("concept"))).findFirst().orElseThrow().getString("status")).isEqualTo("DUE");
    }
    @Test void T_04_22_zeroDueCardUsesSetupAndCheckoutRejectsUnsafeRedirectsAndUnpayableMembers() throws Exception {
        providers(true);String single=offer(PlanType.SINGLE_CLASS,1,2000);var body=request();body.put("planId",single);body.set("payment",mapper.valueToTree(Map.of("type","CARD")));var submitted=submit(body);String id=submitted.path("memberId").asText();
        var checkout=new LinkedHashMap<>(Map.of("memberId",id,"signupToken",submitted.path("signupToken").asText(),"successUrl","https://"+host+"/success","cancelUrl","https://"+host+"/cancel"));
        for(String bad:List.of("http://"+host,"https://wrong.example.test","https://user@"+host,"https://"+host+":9999","not a uri")) {checkout.put("successUrl",bad);result(postJson("/checkout-sessions",checkout).header("Idempotency-Key",UUID.randomUUID()),400);}
        checkout.put("successUrl","https://"+host+"/success");String session=result(postJson("/checkout-sessions",checkout).header("Idempotency-Key",UUID.randomUUID()),201).path("checkoutSessionId").asText();
        assertThat(fake.request(session).mode()).isEqualTo("setup");assertThat(fake.request(session).lines()).isEmpty();assertThat(fake.request(session).setupFutureUsage()).isEqualTo("off_session");fake.complete(session);
        var request=new LinkedHashMap<String,Object>(Map.of("version",member(id).getLong("version"),"dogs",List.of(Map.of("dogId",dog(id),"levelId",level))));result(admin(postJson("/members/"+id+"/validation",request)),200);
        var plain=request();plain.put("planId",single);var unpaid=submit(plain);checkout.put("memberId",unpaid.path("memberId").asText());checkout.put("signupToken",unpaid.path("signupToken").asText());
        assertThat(result(postJson("/checkout-sessions",checkout).header("Idempotency-Key",UUID.randomUUID()),409).path("code").asText()).isEqualTo("INVALID_STATE");
        providers(false);assertThat(result(postJson("/checkout-sessions",checkout).header("Idempotency-Key",UUID.randomUUID()),422).path("code").asText()).isEqualTo("PAYMENT_PROVIDER_NOT_ENABLED");
    }
    @Test void T_04_26_catalogAndCountryVariantsKeepModuleGatesAndEmptyPlanSignup() throws Exception {
        String pack=offer(PlanType.PACK,1,13500),family=offer(PlanType.MONTHLY,2,9000);
        try(var tenant=com.agilityhub.core.shared.application.TenantContext.open(club)) {
            assertThat(policy.additionalFee(plan,family).amountMinor()).isEqualTo(3000);
            assertThat(policy.additionalFee(null,plan).amountMinor()).isEqualTo(3000);
            assertThat(policy.additionalFee(plan,pack).amountMinor()).isZero();
        }
        var config=result(get("/api/v1/signup").header("Host",host),200);assertThat(config.path("plans")).hasSize(3);
        mongo.getCollection("clubs").updateOne(new Document("_id",club),new Document("$set",new Document("modules",List.of("BILLING"))));configs.invalidate(club);signupService.invalidateConfiguration(club);
        var body=request();body.put("planId",pack);assertThat(result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),422).path("code").asText()).isEqualTo("PLAN_NOT_AVAILABLE");
        body=request();body.set("familyGroupClaim",mapper.valueToTree(Map.of("holderName","Example Holder","dogName","Example Dog","leavePending",true)));result(postJson("/signup",body).header("Idempotency-Key",UUID.randomUUID()),400);
        mongo.getCollection("plans").updateMany(new Document("clubId",club),new Document("$set",new Document("showOnSignup",false)));signupService.invalidateConfiguration(club);
        body=request();body.remove("planId");String id=submit(body).path("memberId").asText();assertThat(member(id).get("planId")).isNull();assertThat(collection("upfront_payments")).isEmpty();
        try(var tenant=com.agilityhub.core.shared.application.TenantContext.open(club)) {assertThat(policy.additionalFee(null,null).amountMinor()).isZero();}
    }
    @Test void T_04_17_newAccountWelcomeLinkExchangesForAnAuthenticatedMemberSession() throws Exception {
        var body=request();String email="welcome."+UUID.randomUUID()+"@example.test";
        ((ObjectNode)body.get("person")).set("emails",mapper.valueToTree(List.of(email)));
        String id=submit(body).path("memberId").asText();validate(id,16000);dispatch();
        // Equal-clock outbox events are ordered by UUID, so N-01 can arrive after N-02.
        String notificationId=collection("notifications").stream().filter(n -> "N-02".equals(n.getString("code"))
                && "EMAIL".equals(n.getString("channel")) && email.equals(n.getString("recipientEmail")))
                .findFirst().orElseThrow().getString("_id");
        var welcome=((com.agilityhub.core.clubs.messaging.application.FakeEmailSender)sender).messages().stream()
                .filter(m -> notificationId.equals(m.tags().get("notificationId"))).toList();
        assertThat(welcome).hasSize(1);
        var match=java.util.regex.Pattern.compile("[?]t=([A-Za-z0-9_-]{43})").matcher(welcome.getFirst().text());assertThat(match.find()).isTrue();
        var login=result(post("/oauth2/token").header("Host",host).contentType("application/x-www-form-urlencoded")
                .param("grant_type","urn:agilityhub:grant:magic-link").param("client_id","clubs-app").param("token",match.group(1)),200);
        var me=result(get("/api/v1/me").header("Host",host).header("Authorization","Bearer "+login.path("access_token").asText()),200);
        assertThat(me.at("/account/id").asText()).isEqualTo(member(id).getString("accountId"));
        assertThat(me.at("/membership/memberId").asText()).isEqualTo(id);
    }
}
